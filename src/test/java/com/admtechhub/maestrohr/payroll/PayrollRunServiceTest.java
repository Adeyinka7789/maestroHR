package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.kafka.PayrollEventProducer;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.loan.LoanPolicyService;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.dto.PayrollRunResponse;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import static org.mockito.ArgumentMatchers.eq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PayrollRunServiceTest {

    @Mock PayrollRunRepository payrollRunRepository;
    @Mock PayrollEntryRepository payrollEntryRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock PayrollEngine payrollEngine;
    @Mock NotificationService notificationService;
    @Mock PayrollEventProducer payrollEventProducer;
    @Mock LeaveService leaveService;
    @Mock AttendanceService attendanceService;
    @Mock LoanService loanService;

    @InjectMocks PayrollRunService payrollRunService;

    static final UUID TENANT  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(TENANT.toString());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void initiatePayroll_duplicatePeriod_throws() {
        when(payrollRunRepository.existsByTenant_IdAndPayrollMonthAndPayrollYear(TENANT, 6, 2026))
                .thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> payrollRunService.initiatePayroll(6, 2026, USER_ID));

        verify(payrollRunRepository, never()).save(any());
    }

    @Test
    void approvePayroll_wrongStatus_throws() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = PayrollRun.builder().status(PayrollStatus.DRAFT).build();
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        assertThrows(IllegalStateException.class,
                () -> payrollRunService.approvePayroll(runId, USER_ID));
    }

    @Test
    void approvePayroll_loanDriftDetected_throws() {
        UUID runId = UUID.randomUUID();
        User initiator = User.builder()
                .email("hr@company.com")
                .role(UserRole.HR_ADMIN)
                .build();
        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.PENDING_APPROVAL)
                .initiatedBy(initiator)
                .build();

        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder()
                        .email("mgr@company.com")
                        .role(UserRole.DEPT_MANAGER)
                        .build()));
        doThrow(new IllegalStateException("Loan deductions have changed since payroll was computed"))
                .when(loanService).verifyDeductionsCurrent(any());

        assertThrows(IllegalStateException.class,
                () -> payrollRunService.approvePayroll(runId, USER_ID));

        verify(payrollRunRepository, never()).save(any());
    }

    @Test
    void markAsPaid_wrongStatus_throws() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = PayrollRun.builder().status(PayrollStatus.DRAFT).build();
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        assertThrows(IllegalStateException.class,
                () -> payrollRunService.markAsPaid(runId));
    }

    @Test
    void submitForApproval_fromDraft_succeeds() {
        UUID runId = UUID.randomUUID();
        User initiator = User.builder()
                .email("hr@company.com")
                .role(UserRole.HR_ADMIN)
                .build();

        Tenant tenant = Tenant.builder()
                .companyName("Acme Ltd")
                .build();
        tenant.setId(TENANT);

        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.DRAFT)
                .initiatedBy(initiator)
                .tenant(tenant)
                .payrollMonth(6)
                .payrollYear(2026)
                .build();

        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));
        when(payrollEntryRepository.findByPayrollRunId(eq(runId), any(UUID.class)))
                .thenReturn(List.of(mock(PayrollEntry.class)));
        when(payrollRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PayrollRunResponse response = payrollRunService.submitForApproval(runId);

        assertNotNull(response);
        verify(payrollRunRepository).save(argThat(r -> r.getStatus() == PayrollStatus.PENDING_APPROVAL));
    }

    // ════════════════════════════════════════════════════════════════════════
    // computePayroll() — proration date-math (daysWorked derivation)
    //
    // All tests below use June 2026 as the payroll period: 30 days, starting on a Monday
    // (June 1, 2026). Weekday facts were confirmed independently via the system calendar
    // (`date -d 2026-06-DD +%A`), not derived from the code under test:
    //   June  1 = Monday   (period start)
    //   June  5 = Friday       June  7 = Sunday
    //   June 10 = Wednesday    June 14 = Sunday
    //   June 15 = Monday       June 20 = Saturday    June 21 = Sunday
    //   June 27 = Saturday     June 28 = Sunday
    //   June 30 = Tuesday  (period end)
    // Sundays in June 2026: 7, 14, 21, 28 (four). Full-period working days = 30 − 4 = 26.
    // ════════════════════════════════════════════════════════════════════════

    private PayrollRun draftRun(UUID runId, int month, int year) {
        Tenant tenant = Tenant.builder().companyName("Acme Ltd").build();
        tenant.setId(TENANT);
        User initiator = User.builder().email("hr@company.com").role(UserRole.HR_ADMIN).build();
        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.DRAFT)
                .initiatedBy(initiator)
                .tenant(tenant)
                .payrollMonth(month)
                .payrollYear(year)
                .build();

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(payrollEntryRepository.findByPayrollRunId(eq(runId), any(UUID.class))).thenReturn(List.of());
        when(payrollRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return run;
    }

    private Employee employee(LocalDate hireDate, LocalDate terminationDate) {
        Tenant tenant = Tenant.builder().companyName("Acme Ltd").build();
        tenant.setId(TENANT);
        Employee e = Employee.builder()
                .tenant(tenant)
                .employeeNumber("EMP-" + UUID.randomUUID())
                .firstName("Test").lastName("Employee")
                .employmentStartDate(hireDate)
                .terminationDate(terminationDate)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private void stubEmployees(List<Employee> employees) {
        when(employeeRepository.findActiveOrTerminatedDuringPeriod(any(), any(), any(), any()))
                .thenReturn(employees);
    }

    /** Zero unpaid-leave/absent/late/loan data for every employee — isolates a test to date math. */
    private void stubBatchesEmpty() {
        when(leaveService.getUnpaidLeaveDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(attendanceService.getAbsentDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(attendanceService.getLateDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(loanService.computeLoanDeductionsBatch(any())).thenReturn(Map.of());
    }

    /**
     * Stubs the mocked PayrollEngine to echo back whatever daysWorked/workingDays/loanDeduction
     * PayrollRunService passed in, wrapped in an otherwise-zeroed PayrollResult. This lets tests
     * assert on the SUT's own date-math output (via the captured invocation arguments) without
     * depending on PayrollEngine's real arithmetic, which is already covered by PayrollEngineTest.
     */
    private void stubEngineEchoesInputs() {
        when(payrollEngine.calculateEmployeePayroll(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    int daysWorked = inv.getArgument(1);
                    int workingDays = inv.getArgument(2);
                    long loanDeduction = inv.getArgument(6);
                    return PayrollEngine.PayrollResult.builder()
                            .basicSalary(0L).housingAllowance(0L).transportAllowance(0L).otherAllowances(0L)
                            .grossSalary(0L).pensionEmployee(0L).pensionEmployer(0L).nhfDeduction(0L)
                            .nsitfEmployer(0L).payeTax(0L).otherDeductions(0L)
                            .unpaidLeaveDeduction(0L).attendanceDeduction(0L).lateDeduction(0L)
                            .loanDeduction(loanDeduction).loanDeductionCapped(false)
                            .netSalary(0L)
                            .daysWorked(daysWorked).workingDays(workingDays)
                            .isProrated(daysWorked < workingDays)
                            .taxableIncome(0L)
                            .build();
                });
    }

    // 1 — Full month, no hire/termination: daysWorked must equal the full working-day count.
    @Test
    void computePayroll_fullMonth_daysWorkedEqualsFullWorkingDayCount() {
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2020, 1, 1), null); // hired long before the period
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> workingDaysCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), workingDaysCap.capture(), anyInt(), anyInt(), anyInt(), anyLong());

        assertEquals(26, workingDaysCap.getValue(), "June 2026 has 26 working days (30 − 4 Sundays)");
        assertEquals(26, daysWorkedCap.getValue(), "a full-period employee must be credited every working day");
    }

    // 2 — Mid-month hire (day 15 of 30): daysWorked covers only post-hire working days.
    @Test
    void computePayroll_midMonthHire_daysWorkedCoversOnlyPostHireWorkingDays() {
        // Hired 2026-06-15 (Monday). Dates 15..30 = 16 calendar days; Sundays in that span
        // are the 21st and 28th (2) → 16 − 2 = 14 working days.
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2026, 6, 15), null);
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong());

        assertEquals(14, daysWorkedCap.getValue());
    }

    // 3 — Mid-month termination (day 10): daysWorked covers only pre-termination working days.
    @Test
    void computePayroll_midMonthTermination_daysWorkedCoversOnlyPreTerminationWorkingDays() {
        // Terminated 2026-06-10 (Wednesday). Dates 1..10 = 10 calendar days; the only Sunday
        // in that span is the 7th → 10 − 1 = 9 working days.
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 6, 10));
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong());

        assertEquals(9, daysWorkedCap.getValue());
    }

    // 4 — Hire AND termination in the same period (short-tenure employee).
    @Test
    void computePayroll_hireAndTerminationSamePeriod_daysWorkedCoversOnlyTheEmploymentWindow() {
        // Hired 2026-06-05 (Friday), terminated 2026-06-20 (Saturday). Dates 5..20 = 16
        // calendar days; Sundays in that span are the 7th and 14th (2) → 16 − 2 = 14.
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 20));
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong());

        assertEquals(14, daysWorkedCap.getValue());
    }

    // 5 — Hire date falls ON a Sunday: that Sunday must not itself count as worked.
    @Test
    void computePayroll_hireDateFallsOnSunday_thatSundayIsNotCountedAsWorked() {
        // Hired 2026-06-14, itself a Sunday. Dates 14..30 = 17 calendar days; Sundays in
        // that span are the 14th, 21st, and 28th (3) → 17 − 3 = 14 working days. The hire
        // date being a Sunday grants no "free" boundary day — countWorkingDays excludes
        // every Sunday in range regardless of whether it's an endpoint.
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2026, 6, 14), null);
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong());

        assertEquals(14, daysWorkedCap.getValue());
    }

    // 6 — Zero working days: hired after periodEnd must yield 0, never negative, never throw.
    @Test
    void computePayroll_hireDateAfterPeriodEnd_daysWorkedIsZeroNotNegative() {
        // Hired 2026-07-01, one day after the June 2026 period ends. effectiveEnd (June 30)
        // is before effectiveStart (July 1), so PayrollRunService's own guard must
        // short-circuit to 0 rather than calling countWorkingDays with an inverted range.
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2026, 7, 1), null);
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        assertDoesNotThrow(() -> payrollRunService.computePayroll(runId));

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong());

        assertEquals(0, daysWorkedCap.getValue());
    }

    // 7 — Combined with the PAYE minimum-wage exemption: a partial-month employee must still
    // be taxed correctly, because the exemption is judged on NOMINAL (not prorated) gross.
    //
    // Reuses the mid-month-hire scenario from test 2 (hired 2026-06-15 → daysWorked=14,
    // workingDays=26 — independently re-verified there via the system calendar) so this test
    // doesn't need new date-math facts; it composes those already-proven figures with a real
    // PayrollEngine to check the interaction the audit flagged.
    //
    // Pay grade: basic=100,000 kobo, housing=0, transport=0, otherAllowances=12,900,000 kobo
    // → nominal gross = 13,000,000 kobo (₦130,000/month), almost all of it in otherAllowances
    // (which pension/NHF are NOT calculated on) to keep statutory deductions negligible.
    // 13,000,000 × 14/26 = 7,000,000 kobo exactly — the ₦70,000 minimum-wage threshold.
    // Verified independently (Python, not the code under test):
    //   prorated basic=53,846, otherAllowances=6,946,154 → prorated gross=7,000,000
    //   pension=4,308 (8% of prorated basic; housing/transport are 0), NHF=1,346 (2.5% of
    //   prorated basic) → annualGrossTaxable = 7,000,000×12 − 4,308×12 − 1,346×12 = 83,932,152
    //   → Band 1 (first 80,000,000 @ 0%) absorbs 80,000,000, leaving 3,932,152 in Band 2 @ 15%
    //   → annualPAYE = round(3,932,152 × 0.15) = 589,823 → monthlyPAYE = 589,823 / 12 = 49,151
    //
    // If the exemption were (incorrectly) judged on this PRORATED gross, "7,000,000 <=
    // 7,000,000" would be true and PAYE would be wrongly forced to 0. Judged correctly on the
    // NOMINAL ₦130,000 gross (well above ₦70,000), the exemption must NOT trigger, and PAYE
    // must come out to exactly 49,151 kobo — small, but non-zero.
    @Test
    void computePayroll_partialMonthEmployee_stillTaxedOnNominalGross_notProratedGross() {
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        Employee emp = employee(LocalDate.of(2026, 6, 15), null);
        stubEmployees(List.of(emp));
        stubBatchesEmpty();
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Integer> daysWorkedCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> workingDaysCap = ArgumentCaptor.forClass(Integer.class);
        verify(payrollEngine).calculateEmployeePayroll(
                any(), daysWorkedCap.capture(), workingDaysCap.capture(), anyInt(), anyInt(), anyInt(), anyLong());
        int daysWorked = daysWorkedCap.getValue();
        int workingDays = workingDaysCap.getValue();
        assertEquals(14, daysWorked);
        assertEquals(26, workingDays);

        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getLongOrDefault(anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(platformSettingsService.getDoubleOrDefault(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));
        LoanPolicyService loanPolicyService = mock(LoanPolicyService.class);
        when(loanPolicyService.getPolicyForEmployee(any())).thenReturn(Optional.empty());
        AttendanceService attendanceServiceForRealEngine = mock(AttendanceService.class);
        when(attendanceServiceForRealEngine.getEffectivePolicy(any())).thenReturn(Optional.empty());

        PayrollEngine realEngine = new PayrollEngine(
                new PensionCalculator(platformSettingsService),
                new NHFCalculator(platformSettingsService),
                new NSITFCalculator(platformSettingsService),
                new PAYECalculator(platformSettingsService),
                loanPolicyService,
                platformSettingsService,
                attendanceServiceForRealEngine);

        Employee partialMonthEmployee = Employee.builder()
                .firstName("Partial").lastName("Month")
                .employeeNumber("EMP-PARTIAL")
                .payGrade(PayGrade.builder()
                        .name("Mostly Allowance")
                        .basicSalary(100_000L)
                        .housingAllowance(0L)
                        .transportAllowance(0L)
                        .otherAllowances(12_900_000L)
                        .build())
                .build();

        PayrollEngine.PayrollResult result =
                realEngine.calculateEmployeePayroll(partialMonthEmployee, daysWorked, workingDays, 0, 0, 0, 0L);

        assertEquals(7_000_000L, result.getGrossSalary(),
                "prorated gross must land exactly at the ₦70,000 exemption threshold for this scenario");
        assertEquals(49_151L, result.getPayeTax(),
                "nominal (un-prorated) gross of ₦130,000 must still be taxed, not exempted, even though "
                        + "the prorated gross itself sits exactly at the minimum-wage boundary");
    }

    // 8 — Batch-prefetch mapping: each employee's own unpaid-leave/absent/late/loan data must
    // be applied to THEM, not another employee or a leftover default.
    @Test
    void computePayroll_multipleEmployees_batchMapsApplyToCorrectEmployee() {
        UUID runId = UUID.randomUUID();
        draftRun(runId, 6, 2026);

        // All three worked the full period (hired long ago, no termination) so daysWorked is
        // uniform — isolates this test to batch-map correctness rather than date math.
        Employee emp1 = employee(LocalDate.of(2020, 1, 1), null);
        Employee emp2 = employee(LocalDate.of(2020, 1, 1), null);
        Employee emp3 = employee(LocalDate.of(2020, 1, 1), null);
        stubEmployees(List.of(emp1, emp2, emp3));

        when(leaveService.getUnpaidLeaveDaysBatch(any(), any(), any()))
                .thenReturn(Map.of(emp1.getId(), 2, emp3.getId(), 1)); // emp2 omitted → defaults to 0
        when(attendanceService.getAbsentDaysBatch(any(), any(), any()))
                .thenReturn(Map.of(emp2.getId(), 3));
        when(attendanceService.getLateDaysBatch(any(), any(), any()))
                .thenReturn(Map.of(emp2.getId(), 1, emp3.getId(), 2));
        when(loanService.computeLoanDeductionsBatch(any()))
                .thenReturn(Map.of(emp1.getId(), 500_000L, emp3.getId(), 1_000_000L));
        stubEngineEchoesInputs();

        payrollRunService.computePayroll(runId);

        ArgumentCaptor<Employee> employeeCap = ArgumentCaptor.forClass(Employee.class);
        ArgumentCaptor<Integer> unpaidCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> absentCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> lateCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> loanCap = ArgumentCaptor.forClass(Long.class);
        verify(payrollEngine, times(3)).calculateEmployeePayroll(
                employeeCap.capture(), anyInt(), anyInt(), unpaidCap.capture(), absentCap.capture(),
                lateCap.capture(), loanCap.capture());

        Map<UUID, Integer> unpaidByEmployee = new HashMap<>();
        Map<UUID, Integer> absentByEmployee = new HashMap<>();
        Map<UUID, Integer> lateByEmployeeEngineArg = new HashMap<>();
        Map<UUID, Long> loanByEmployee = new HashMap<>();
        List<Employee> capturedEmployees = employeeCap.getAllValues();
        for (int i = 0; i < capturedEmployees.size(); i++) {
            UUID id = capturedEmployees.get(i).getId();
            unpaidByEmployee.put(id, unpaidCap.getAllValues().get(i));
            absentByEmployee.put(id, absentCap.getAllValues().get(i));
            lateByEmployeeEngineArg.put(id, lateCap.getAllValues().get(i));
            loanByEmployee.put(id, loanCap.getAllValues().get(i));
        }

        assertEquals(2, unpaidByEmployee.get(emp1.getId()));
        assertEquals(0, unpaidByEmployee.get(emp2.getId()));
        assertEquals(1, unpaidByEmployee.get(emp3.getId()));

        assertEquals(0, absentByEmployee.get(emp1.getId()));
        assertEquals(3, absentByEmployee.get(emp2.getId()));
        assertEquals(0, absentByEmployee.get(emp3.getId()));

        // lateDaysMap must flow into the new calculateEmployeePayroll(lateDays) parameter,
        // not just onto PayrollEntry.lateDaysInPeriod (verified separately below).
        assertEquals(0, lateByEmployeeEngineArg.get(emp1.getId()));
        assertEquals(1, lateByEmployeeEngineArg.get(emp2.getId()));
        assertEquals(2, lateByEmployeeEngineArg.get(emp3.getId()));

        assertEquals(500_000L, loanByEmployee.get(emp1.getId()));
        assertEquals(0L, loanByEmployee.get(emp2.getId()));
        assertEquals(1_000_000L, loanByEmployee.get(emp3.getId()));

        // lateDaysInPeriod is never passed to PayrollEngine — it's stored straight onto the
        // PayrollEntry — so verify it via the saved entries instead.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayrollEntry>> entriesCap = ArgumentCaptor.forClass(List.class);
        verify(payrollEntryRepository).saveAll(entriesCap.capture());
        Map<UUID, Integer> lateByEmployee = new HashMap<>();
        for (PayrollEntry entry : entriesCap.getValue()) {
            lateByEmployee.put(entry.getEmployee().getId(), entry.getLateDaysInPeriod());
        }
        assertEquals(0, lateByEmployee.get(emp1.getId()));
        assertEquals(1, lateByEmployee.get(emp2.getId()));
        assertEquals(2, lateByEmployee.get(emp3.getId()));
    }
}