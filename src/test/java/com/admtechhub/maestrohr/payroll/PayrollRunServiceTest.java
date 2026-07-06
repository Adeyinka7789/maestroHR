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
import com.admtechhub.maestrohr.payroll.event.PayrollApprovedAppEvent;
import com.admtechhub.maestrohr.payroll.event.PayrollMarkedPaidAppEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.ArrayList;
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
    @Mock ApplicationEventPublisher eventPublisher;

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

    /**
     * Simulates the race idx_payroll_runs_one_active_period (V53) closes: the existsBy...
     * pre-check passes (returns false — the race window between two concurrent
     * initiatePayroll calls for the same tenant/month/year), but the subsequent
     * saveAndFlush collides with the unique index and Postgres raises a constraint
     * violation. initiatePayroll must translate that into a clean IllegalStateException
     * rather than letting the raw DataIntegrityViolationException surface, and must not
     * have sent any notification (the failure happens before that side effect).
     */
    @Test
    void initiatePayroll_raceLosesToUniqueIndex_translatesToCleanIllegalStateException() {
        when(payrollRunRepository.existsByTenant_IdAndPayrollMonthAndPayrollYear(TENANT, 6, 2026))
                .thenReturn(false);

        Tenant tenant = Tenant.builder().companyName("Acme Ltd").build();
        tenant.setId(TENANT);
        when(tenantRepository.findById(TENANT)).thenReturn(Optional.of(tenant));

        User initiator = User.builder().email("hr@company.com").role(UserRole.HR_ADMIN).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(initiator));

        when(payrollRunRepository.saveAndFlush(any(PayrollRun.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"idx_payroll_runs_one_active_period\""));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> payrollRunService.initiatePayroll(6, 2026, USER_ID));

        assertEquals("Payroll for 6/2026 already exists", ex.getMessage());
        verify(notificationService, never()).createInAppNotification(any(), any(), any(), any(), any());
    }

    @Test
    void approvePayroll_wrongStatus_throws() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = PayrollRun.builder().status(PayrollStatus.DRAFT).build();
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        assertThrows(IllegalStateException.class,
                () -> payrollRunService.approvePayroll(runId, USER_ID));
    }

    /**
     * Replaces the old approvePayroll_loanDriftDetected_throws: the drift guard moved from
     * LoanService.verifyDeductionsCurrent (loan-only) to PayrollRunService's snapshot-based
     * check, which covers unpaidLeaveDays/absentDays/lateDays/loanDeduction together. This
     * simulates a loan deduction that changed since compute (e.g. paused/cancelled) on an
     * otherwise-ordinary, non-capped entry.
     */
    @Test
    void approvePayroll_deductionSnapshotDrifted_throws() {
        UUID runId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        User initiator = User.builder().email("hr@company.com").role(UserRole.HR_ADMIN).build();

        Employee emp = mock(Employee.class);
        when(emp.getId()).thenReturn(empId);
        when(emp.getFullName()).thenReturn("Jane Doe");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getEmployee()).thenReturn(emp);
        // Snapshot recorded at compute time: 0 unpaid/absent/late days, loanDeduction=25,000.
        when(entry.getDeductionSnapshot()).thenReturn("0:0:0:25000");

        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.PENDING_APPROVAL)
                .initiatedBy(initiator)
                .payrollMonth(6)
                .payrollYear(2026)
                .entries(new ArrayList<>(List.of(entry)))
                .build();

        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().email("mgr@company.com").role(UserRole.DEPT_MANAGER).build()));

        when(leaveService.getUnpaidLeaveDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(attendanceService.getAbsentDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(attendanceService.getLateDaysBatch(any(), any(), any())).thenReturn(Map.of());
        // The loan's installment changed since compute (e.g. it was paused then resumed at a
        // different amount) - re-querying now gives 0, which no longer matches the snapshot.
        when(loanService.computeLoanDeductionsBatch(any())).thenReturn(Map.of(empId, 0L));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> payrollRunService.approvePayroll(runId, USER_ID));
        assertTrue(ex.getMessage().contains("recompute"));

        verify(loanService, never()).applyRepaymentsForRun(any(), any());
        verify(payrollRunRepository, never()).save(any());
    }

    /**
     * The specific gap the old LoanService.verifyDeductionsCurrent left open: it unconditionally
     * skipped any entry where loanDeductionCapped=true, so a loan cancelled after compute on a
     * capped entry silently passed approval. The new snapshot stores the RAW loanDeduction input
     * (not the capped value actually charged on the payslip), so it still detects this drift even
     * though the entry is capped.
     */
    @Test
    void approvePayroll_cancelledLoanOnCappedEntry_stillTripsDriftGuard() {
        UUID runId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        User initiator = User.builder().email("hr@company.com").role(UserRole.HR_ADMIN).build();

        Employee emp = mock(Employee.class);
        when(emp.getId()).thenReturn(empId);
        when(emp.getFullName()).thenReturn("Capped Employee");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getEmployee()).thenReturn(emp);
        // Computed with a raw loan deduction input of 500,000, net-floor-capped down to a
        // lower amount on the actual payslip. loanDeductionCapped=true (implied by the stored
        // snapshot recording the RAW, pre-cap input) is exactly the condition the OLD
        // LoanService.verifyDeductionsCurrent used to skip entirely - the new guard never even
        // reads loanDeductionCapped/loanDeduction, so those fields aren't stubbed here.
        when(entry.getDeductionSnapshot()).thenReturn("0:0:0:500000");

        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.PENDING_APPROVAL)
                .initiatedBy(initiator)
                .payrollMonth(6)
                .payrollYear(2026)
                .entries(new ArrayList<>(List.of(entry)))
                .build();

        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().email("mgr@company.com").role(UserRole.DEPT_MANAGER).build()));

        when(leaveService.getUnpaidLeaveDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(attendanceService.getAbsentDaysBatch(any(), any(), any())).thenReturn(Map.of());
        when(attendanceService.getLateDaysBatch(any(), any(), any())).thenReturn(Map.of());
        // The loan was cancelled between compute and approval - it no longer contributes.
        when(loanService.computeLoanDeductionsBatch(any())).thenReturn(Map.of(empId, 0L));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> payrollRunService.approvePayroll(runId, USER_ID));
        assertTrue(ex.getMessage().contains("recompute"));

        verify(loanService, never()).applyRepaymentsForRun(any(), any());
        verify(payrollRunRepository, never()).save(any());
    }

    /**
     * Fix B: approvePayroll must publish a PayrollApprovedAppEvent for the AFTER_COMMIT
     * listener to pick up, instead of calling NotificationService/PayrollEventProducer
     * directly inside the approval transaction.
     */
    @Test
    void approvePayroll_success_publishesAppEvent_doesNotCallNotificationDirectly() {
        UUID runId = UUID.randomUUID();
        User initiator = User.builder().email("hr@company.com").role(UserRole.HR_ADMIN).build();
        User approver = User.builder().email("mgr@company.com").role(UserRole.DEPT_MANAGER).build();
        Tenant tenant = Tenant.builder().companyName("Acme Ltd").build();
        tenant.setId(TENANT);

        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.PENDING_APPROVAL)
                .initiatedBy(initiator)
                .tenant(tenant)
                .payrollMonth(6)
                .payrollYear(2026)
                .entries(new ArrayList<>())
                .build();

        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(approver));
        when(payrollRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        payrollRunService.approvePayroll(runId, USER_ID);

        ArgumentCaptor<PayrollApprovedAppEvent> eventCap = ArgumentCaptor.forClass(PayrollApprovedAppEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertEquals(runId, eventCap.getValue().payrollRunId());
        assertEquals("mgr@company.com", eventCap.getValue().approvedByEmail());
        assertEquals("hr@company.com", eventCap.getValue().initiatedByEmail());

        verify(payrollEventProducer, never()).publishPayrollApproved(any(), any());
        verify(notificationService, never()).createInAppNotification(any(), any(), any(), any(), any());
        verify(notificationService, never()).sendPayslipNotification(any(), any(), any());
    }

    @Test
    void markAsPaid_wrongStatus_throws() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = PayrollRun.builder().status(PayrollStatus.DRAFT).build();
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        assertThrows(IllegalStateException.class,
                () -> payrollRunService.markAsPaid(runId));
    }

    /**
     * Fix B: markAsPaid must publish a PayrollMarkedPaidAppEvent for the AFTER_COMMIT listener
     * to pick up, instead of calling NotificationService directly inside the completion
     * transaction.
     */
    @Test
    void markAsPaid_success_publishesAppEvent_doesNotCallNotificationDirectly() {
        UUID runId = UUID.randomUUID();
        User initiator = User.builder().email("hr@company.com").role(UserRole.HR_ADMIN).build();
        Tenant tenant = Tenant.builder().companyName("Acme Ltd").build();
        tenant.setId(TENANT);

        PayrollRun run = PayrollRun.builder()
                .status(PayrollStatus.APPROVED)
                .initiatedBy(initiator)
                .tenant(tenant)
                .payrollMonth(6)
                .payrollYear(2026)
                .build();

        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));
        when(payrollEntryRepository.findByPayrollRunId(eq(runId), any(UUID.class))).thenReturn(List.of());
        when(payrollRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        payrollRunService.markAsPaid(runId);

        ArgumentCaptor<PayrollMarkedPaidAppEvent> eventCap = ArgumentCaptor.forClass(PayrollMarkedPaidAppEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertEquals(runId, eventCap.getValue().payrollRunId());
        assertEquals("hr@company.com", eventCap.getValue().initiatedByEmail());

        verify(notificationService, never()).createInAppNotification(any(), any(), any(), any(), any());
        verify(notificationService, never()).sendSalaryProcessedNotification(any(), any(), any(), any());
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

    // 7 — Combined with the PAYE minimum-wage exemption AND the nominal-gross annualization
    // (Fix C.4): a partial-month employee must still be taxed at their true (nominal) bracket,
    // then have that tax proportionally withheld for the days actually worked.
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
    //
    // If either the exemption OR the tax banding were (incorrectly) judged on this PRORATED
    // gross, "7,000,000 <= 7,000,000" would exempt this employee entirely (PAYE = 0). Judged
    // correctly on the NOMINAL ₦130,000 gross, the exemption must NOT trigger and the employee
    // must be banded at their true bracket, then have that tax proportionally withheld for the
    // days actually worked. Verified independently (not the code under test):
    //   prorated basic=53,846, otherAllowances=6,946,154 (already proven above via test 2's
    //   date math); pension=4,308 (8% of prorated basic), NHF=1,346 (2.5% of prorated basic)
    //   — these three are UNCHANGED by Fix C.4, which only touches annualization.
    //   NEW annualGross = nominalMonthlyGross × 12 = 13,000,000 × 12 = 156,000,000
    //   annualGrossTaxable = 156,000,000 − 4,308×12 − 1,346×12 = 155,932,152
    //   Band 1 (first 80,000,000 @ 0%) absorbs 80,000,000, leaving 75,932,152 in Band 2 @ 15%
    //   annualPAYE = round(75,932,152 × 0.15) = 11,389,823 → full-month monthlyPAYE (pre-
    //   proration) = 11,389,823 / 12 = 949,151 (integer division)
    //   PayrollEngine then prorates THIS tax by daysWorked/workingDays (14/26):
    //   payeTax = round(949,151 × 14 / 26) = round(511,081.3...) = 511,081
    //
    // (Note: a "call PayrollEngine again with daysWorked==workingDays and prorate that result"
    // shortcut does NOT reproduce this exactly — that reference call would compute pension/NHF
    // off the FULL nominal basic rather than THIS period's prorated basic, giving a slightly
    // different annualTaxableIncome. The hand-derived value above matches what the engine
    // actually computes for this specific prorated call.)
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
        assertEquals(511_081L, result.getPayeTax(),
                "nominal (un-prorated) gross of ₦130,000 must still be taxed at its true bracket - not the "
                        + "exemption the prorated ₦70,000 gross alone would trigger - then proportionally "
                        + "withheld for the days actually worked this period");
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