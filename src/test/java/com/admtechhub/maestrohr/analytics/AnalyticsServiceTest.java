package com.admtechhub.maestrohr.analytics;

import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.overtime.OvertimeEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock PayrollRunRepository payrollRunRepository;
    @Mock PayrollEntryRepository payrollEntryRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock LeaveRequestRepository leaveRequestRepository;
    @Mock OvertimeEntryRepository overtimeEntryRepository;

    @InjectMocks AnalyticsService service;

    private Department dept(String name) {
        Department d = new Department();
        d.setId(UUID.randomUUID());
        d.setName(name);
        return d;
    }

    private Employee employee(Department d, LocalDate start) {
        Employee e = Employee.builder()
                .firstName("Tayo").lastName("Shonibare").department(d)
                .status(EmployeeStatus.ACTIVE).employmentStartDate(start).build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private PayrollRun run(int month, int year) {
        PayrollRun r = PayrollRun.builder().payrollMonth(month).payrollYear(year)
                .status(PayrollStatus.APPROVED).build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private PayrollEntry entry(Employee e, long gross, long pensionEmployer) {
        return PayrollEntry.builder().employee(e).grossSalary(gross).pensionEmployer(pensionEmployer).build();
    }

    @Test
    void build_computesRcol_andFlagsNoLeaveBurnout() {
        Department sales = dept("Sales");
        Employee emp = employee(sales, LocalDate.now().minusYears(2)); // tenured
        PayrollRun latest = run(7, 2026);

        when(payrollRunRepository.findByStatusInOrderByPeriodDesc(any())).thenReturn(List.of(latest));
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(latest.getId()))
                .thenReturn(List.of(entry(emp, 1_000_000L, 100_000L)));
        when(leaveRequestRepository.findLastApprovedLeaveEndDateByEmployee()).thenReturn(List.of());
        when(overtimeEntryRepository.findApprovedSincePeriodKey(anyInt())).thenReturn(List.of());
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(emp));

        AnalyticsView v = service.build();

        assertThat(v.hasData()).isTrue();
        assertThat(v.headcount()).isEqualTo(1);
        // RCOL = gross 1,000,000 + employer pension 100,000 + NSITF 10,000 + ITF 10,000 = 1,120,000 kobo.
        assertThat(v.totalRcolFormatted()).isEqualTo("₦11,200");
        assertThat(v.totalNsitfFormatted()).isEqualTo("₦100");
        assertThat(v.deptRcol()).hasSize(1);
        assertThat(v.deptRcol().get(0).department()).isEqualTo("Sales");
        assertThat(v.deptRcol().get(0).sharePercent()).isEqualTo("100%");
        // Tenured, no leave on record → burnout flag.
        assertThat(v.burnoutCount()).isEqualTo(1);
        assertThat(v.burnout().get(0).reasons()).contains("No approved leave");
        assertThat(v.hasComparison()).isFalse();
    }

    @Test
    void build_detectsDepartmentalSpike() {
        Department sales = dept("Sales");
        Employee curEmp = employee(sales, LocalDate.now().minusYears(1));
        Employee priEmp = employee(sales, LocalDate.now().minusYears(1));
        PayrollRun latest = run(7, 2026);
        PayrollRun prior = run(6, 2026);

        when(payrollRunRepository.findByStatusInOrderByPeriodDesc(any())).thenReturn(List.of(latest, prior));
        // Sales cost 1,180,000 vs 1,000,000 → +18%.
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(latest.getId()))
                .thenReturn(List.of(entry(curEmp, 1_180_000L, 0L)));
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(prior.getId()))
                .thenReturn(List.of(entry(priEmp, 1_000_000L, 0L)));
        when(overtimeEntryRepository.findByPeriodYearAndPeriodMonthOrderByAmountKoboDesc(2026, 7))
                .thenReturn(List.of());
        when(leaveRequestRepository.findLastApprovedLeaveEndDateByEmployee()).thenReturn(List.of());
        when(overtimeEntryRepository.findApprovedSincePeriodKey(anyInt())).thenReturn(List.of());
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of());

        AnalyticsView v = service.build();

        assertThat(v.hasComparison()).isTrue();
        assertThat(v.spikes()).hasSize(1);
        assertThat(v.spikes().get(0).department()).isEqualTo("Sales");
        assertThat(v.spikes().get(0).changePercent()).isEqualTo("+18%");
        assertThat(v.spikes().get(0).flagged()).isTrue();
        // Two finalized runs → a 2-point RCOL trend sparkline (oldest → newest).
        assertThat(v.hasTrend()).isTrue();
        assertThat(v.rcolTrend()).hasSize(2);
        assertThat(v.rcolTrendLinePoints()).isNotBlank();
    }

    @Test
    void build_noFinalizedRun_hasNoData() {
        when(payrollRunRepository.findByStatusInOrderByPeriodDesc(any())).thenReturn(List.of());

        AnalyticsView v = service.build();

        assertThat(v.hasData()).isFalse();
        assertThat(v.deptRcol()).isEmpty();
        assertThat(v.burnout()).isEmpty();
    }
}
