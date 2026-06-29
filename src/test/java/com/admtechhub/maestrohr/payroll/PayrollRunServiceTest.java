package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.kafka.PayrollEventProducer;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.dto.PayrollRunResponse;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
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
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run));

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

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run));
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
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run));

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

        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(payrollEntryRepository.findByPayrollRunId(runId))
                .thenReturn(List.of(mock(PayrollEntry.class)));
        when(payrollRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PayrollRunResponse response = payrollRunService.submitForApproval(runId);

        assertNotNull(response);
        verify(payrollRunRepository).save(argThat(r -> r.getStatus() == PayrollStatus.PENDING_APPROVAL));
    }
}