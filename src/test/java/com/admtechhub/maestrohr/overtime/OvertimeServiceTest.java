package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.adjustment.PayrollAdjustmentService;
import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.employee.PayGrade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OvertimeServiceTest {

    @Mock OvertimePolicyRepository policyRepository;
    @Mock OvertimeEntryRepository entryRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock PayrollAdjustmentService payrollAdjustmentService;
    @Mock PublicHolidayService publicHolidayService;
    @Mock com.admtechhub.maestrohr.notification.NotificationService notificationService;

    @InjectMocks OvertimeService overtimeService;

    static final UUID TENANT = UUID.randomUUID();
    static final UUID EMP = UUID.randomUUID();
    static final UUID ENTRY = UUID.randomUUID();
    static final UUID ADJ = UUID.randomUUID();

    @BeforeEach void bind() { TenantContext.setCurrentTenant(TENANT.toString()); }
    @AfterEach void clear() { TenantContext.clear(); }

    private AttendanceRecord record(LocalDate date, String hours) {
        return AttendanceRecord.builder().attendanceDate(date).hoursWorked(new BigDecimal(hours)).build();
    }

    // Default policy (8h/day, 173h/month, 1.5x weekday, 2.0x weekend). Gross ₦17,300 → ₦100/hr.
    // Wed 1 Jul 2026 = 10h worked → 2 weekday OT hours; Sat 4 Jul 2026 = 5h → 5 weekend OT hours.
    // amount = 2·10000·1.5 + 5·10000·2.0 = 30,000 + 100,000 = 130,000 kobo.
    @Test
    void computeForPeriod_classifiesWeekdayVsWeekend_andRatesOvertime() {
        PayGrade pg = PayGrade.builder().basicSalary(1_730_000L).build();
        Employee emp = Employee.builder()
                .status(EmployeeStatus.ACTIVE).payGrade(pg)
                .firstName("Sam").lastName("Bello").jobTitle("Guard").build();
        emp.setId(EMP);

        when(policyRepository.findFirstByActiveTrue()).thenReturn(Optional.empty());
        when(publicHolidayService.activeDatesBetween(any(), any())).thenReturn(java.util.Set.of());
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(eq(EMP), any(), any(), any()))
                .thenReturn(List.of(
                        record(LocalDate.of(2026, 7, 1), "10"),   // Wednesday
                        record(LocalDate.of(2026, 7, 4), "5")));   // Saturday
        when(entryRepository.findByEmployeeIdAndPeriodYearAndPeriodMonth(EMP, 2026, 7))
                .thenReturn(Optional.empty());
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OvertimeService.ComputeResult result = overtimeService.computeForPeriod(7, 2026);

        assertThat(result.employeesScanned()).isEqualTo(1);
        assertThat(result.entriesWithOvertime()).isEqualTo(1);

        ArgumentCaptor<OvertimeEntry> cap = ArgumentCaptor.forClass(OvertimeEntry.class);
        verify(entryRepository).save(cap.capture());
        OvertimeEntry e = cap.getValue();
        assertThat(e.getWeekdayOtHours()).isEqualByComparingTo("2.00");
        assertThat(e.getWeekendOtHours()).isEqualByComparingTo("5.00");
        assertThat(e.getHourlyRateKobo()).isEqualTo(10_000L);
        assertThat(e.getAmountKobo()).isEqualTo(130_000L);
        assertThat(e.getStatus()).isEqualTo(OvertimeStatus.DRAFT);
    }

    // Wed 1 Jul 2026 marked as a public holiday → its 10h bill entirely at the holiday rate (2.0×),
    // not as weekday overtime. Sat 4 Jul stays weekend (2.0×).
    // amount = 10·10000·2.0 (holiday) + 5·10000·2.0 (weekend) = 200,000 + 100,000 = 300,000.
    @Test
    void computeForPeriod_countsHolidayHoursAtHolidayRate() {
        PayGrade pg = PayGrade.builder().basicSalary(1_730_000L).build();
        Employee emp = Employee.builder()
                .status(EmployeeStatus.ACTIVE).payGrade(pg)
                .firstName("Sam").lastName("Bello").jobTitle("Guard").build();
        emp.setId(EMP);

        when(policyRepository.findFirstByActiveTrue()).thenReturn(Optional.empty());
        when(publicHolidayService.activeDatesBetween(any(), any()))
                .thenReturn(java.util.Set.of(LocalDate.of(2026, 7, 1)));
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(eq(EMP), any(), any(), any()))
                .thenReturn(List.of(
                        record(LocalDate.of(2026, 7, 1), "10"),   // Wednesday — but a holiday
                        record(LocalDate.of(2026, 7, 4), "5")));   // Saturday
        when(entryRepository.findByEmployeeIdAndPeriodYearAndPeriodMonth(EMP, 2026, 7))
                .thenReturn(Optional.empty());
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        overtimeService.computeForPeriod(7, 2026);

        ArgumentCaptor<OvertimeEntry> cap = ArgumentCaptor.forClass(OvertimeEntry.class);
        verify(entryRepository).save(cap.capture());
        OvertimeEntry e = cap.getValue();
        assertThat(e.getHolidayOtHours()).isEqualByComparingTo("10.00");
        assertThat(e.getWeekdayOtHours()).isEqualByComparingTo("0.00");
        assertThat(e.getWeekendOtHours()).isEqualByComparingTo("5.00");
        assertThat(e.getAmountKobo()).isEqualTo(300_000L);
    }

    @Test
    void approve_emitsOvertimeAdjustment_andLinksIt() {
        OvertimeEntry e = OvertimeEntry.builder()
                .employeeId(EMP).periodMonth(7).periodYear(2026).amountKobo(130_000L)
                .weekdayOtHours(new BigDecimal("2.00")).weekendOtHours(new BigDecimal("5.00"))
                .status(OvertimeStatus.DRAFT).build();
        e.setId(ENTRY);
        when(entryRepository.findById(ENTRY)).thenReturn(Optional.of(e));
        when(payrollAdjustmentService.createSystemAdjustment(
                eq(EMP), eq("OVERTIME"), eq(130_000L), eq(7), eq(2026), anyString(), eq("hr@acme.ng")))
                .thenReturn(ADJ);
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        overtimeService.approve(ENTRY, "hr@acme.ng");

        assertThat(e.getStatus()).isEqualTo(OvertimeStatus.APPROVED);
        assertThat(e.getPayrollAdjustmentId()).isEqualTo(ADJ);
        assertThat(e.getApprovedBy()).isEqualTo("hr@acme.ng");
        verify(payrollAdjustmentService).createSystemAdjustment(
                eq(EMP), eq("OVERTIME"), eq(130_000L), eq(7), eq(2026), anyString(), eq("hr@acme.ng"));
    }

    @Test
    void approve_nonDraft_throws() {
        OvertimeEntry e = OvertimeEntry.builder().amountKobo(100L).status(OvertimeStatus.APPROVED).build();
        e.setId(ENTRY);
        when(entryRepository.findById(ENTRY)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> overtimeService.approve(ENTRY, "hr@acme.ng"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft");
    }

    @Test
    void reject_cancelsPendingAdjustment_andMarksRejected() {
        OvertimeEntry e = OvertimeEntry.builder()
                .status(OvertimeStatus.APPROVED).payrollAdjustmentId(ADJ).build();
        e.setId(ENTRY);
        when(entryRepository.findById(ENTRY)).thenReturn(Optional.of(e));
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        overtimeService.reject(ENTRY);

        verify(payrollAdjustmentService).cancelIfPending(ADJ);
        assertThat(e.getStatus()).isEqualTo(OvertimeStatus.REJECTED);
    }
}
