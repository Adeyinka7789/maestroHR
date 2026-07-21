package com.admtechhub.maestrohr.leave;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.notification.TermiiClient;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock private LeaveTypeRepository    leaveTypeRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private EmployeeRepository     employeeRepository;
    @Mock private TenantRepository       tenantRepository;
    @Mock private UserRepository         userRepository;
    @Mock private NotificationService    notificationService;
    @Mock private TermiiClient           termiiClient;
    @Mock private AuditTrailService      auditTrailService;

    @InjectMocks private LeaveService leaveService;

    private static final UUID EMPLOYEE_ID   = UUID.randomUUID();
    private static final UUID REQUEST_ID    = UUID.randomUUID();
    private static final UUID LEAVE_TYPE_ID = UUID.randomUUID();
    private static final UUID APPROVER_ID   = UUID.randomUUID();
    private static final UUID TENANT_UUID   = UUID.randomUUID();

    private Employee  employee;
    private LeaveType leaveType;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT_UUID.toString());

        employee  = mock(Employee.class);
        leaveType = mock(LeaveType.class);

        lenient().when(employee.getId()).thenReturn(EMPLOYEE_ID);
        lenient().when(employee.getFullName()).thenReturn("Jane Doe");
        lenient().when(employee.getEmployeeNumber()).thenReturn("EMP-001");
        lenient().when(employee.getEmail()).thenReturn("jane@test.com");
        lenient().when(employee.getPhone()).thenReturn(null);
        lenient().when(employee.getTenant()).thenReturn(mock(com.admtechhub.maestrohr.tenant.Tenant.class));

        lenient().when(leaveType.getId()).thenReturn(LEAVE_TYPE_ID);
        lenient().when(leaveType.getName()).thenReturn("Annual Leave");
        lenient().when(leaveType.getMaxDaysPerYear()).thenReturn(20);

        lenient().when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        lenient().when(leaveTypeRepository.findById(LEAVE_TYPE_ID)).thenReturn(Optional.of(leaveType));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // 1 ── balance = 5 days remaining, request spans 10 working days → throws
    @Test
    void submitLeaveRequest_insufficientBalance_throws() {
        LeaveBalance balance = LeaveBalance.builder()
                .daysRemaining(5).totalDaysEntitled(20).year(2025).build();
        when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                EMPLOYEE_ID, LEAVE_TYPE_ID, LocalDate.now().getYear()))
                .thenReturn(Optional.of(balance));

        // Jul 7–18 2025 = 10 working weekdays
        assertThatThrownBy(() -> leaveService.submitLeaveRequest(
                EMPLOYEE_ID, LEAVE_TYPE_ID,
                LocalDate.of(2025, 7, 7), LocalDate.of(2025, 7, 18),
                "Vacation", null))
                .isInstanceOf(IllegalStateException.class);
    }

    // 2 ── existing approved leave overlaps the requested dates → throws
    @Test
    void submitLeaveRequest_overlappingApproved_throws() {
        LeaveBalance balance = LeaveBalance.builder()
                .daysRemaining(20).totalDaysEntitled(20).year(2025).build();
        when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                EMPLOYEE_ID, LEAVE_TYPE_ID, LocalDate.now().getYear()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.findApprovedLeavesInDateRange(
                eq(EMPLOYEE_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(mock(LeaveRequest.class)));

        assertThatThrownBy(() -> leaveService.submitLeaveRequest(
                EMPLOYEE_ID, LEAVE_TYPE_ID,
                LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 5),
                "Holiday", null))
                .isInstanceOf(IllegalStateException.class);
    }

    // 3 ── valid request → persisted with PENDING status
    @Test
    void submitLeaveRequest_valid_savesRequestAsPending() {
        LeaveBalance balance = LeaveBalance.builder()
                .daysRemaining(20).totalDaysEntitled(20).year(2025).build();
        when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                EMPLOYEE_ID, LEAVE_TYPE_ID, LocalDate.now().getYear()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.findApprovedLeavesInDateRange(
                any(), any(), any()))
                .thenReturn(List.of());
        when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        leaveService.submitLeaveRequest(
                EMPLOYEE_ID, LEAVE_TYPE_ID,
                LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 3),
                "Break", null);

        ArgumentCaptor<LeaveRequest> captor = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(leaveRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    // 4 ── approve: balance deducted by the request's daysRequested
    @Test
    void approveLeaveRequest_deductsLeaveBalance() {
        LeaveRequest pending = LeaveRequest.builder()
                .status(LeaveStatus.PENDING)
                .employee(employee)
                .leaveType(leaveType)
                .startDate(LocalDate.of(2025, 7, 1))
                .endDate(LocalDate.of(2025, 7, 5))
                .daysRequested(5)
                .reason("Vacation")
                .build();
        User approver = mock(User.class);

        when(leaveRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
        when(userRepository.findById(APPROVER_ID)).thenReturn(Optional.of(approver));
        when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                EMPLOYEE_ID, LEAVE_TYPE_ID, 2025))
                .thenReturn(Optional.empty());
        when(leaveBalanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leaveBalanceRepository.deductLeaveDays(EMPLOYEE_ID, LEAVE_TYPE_ID, 2025, 5)).thenReturn(1);
        when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        leaveService.approveLeaveRequest(REQUEST_ID, APPROVER_ID, "Approved");

        verify(leaveBalanceRepository).deductLeaveDays(EMPLOYEE_ID, LEAVE_TYPE_ID, 2025, 5);

        ArgumentCaptor<LeaveRequest> cap = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(leaveRequestRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(LeaveStatus.APPROVED);
    }

    // 4a ── approve when the balance no longer covers the request → throws, nothing deducted/approved
    @Test
    void approveLeaveRequest_insufficientBalance_throwsWithoutDeducting() {
        LeaveRequest pending = LeaveRequest.builder()
                .status(LeaveStatus.PENDING).employee(employee).leaveType(leaveType)
                .startDate(LocalDate.of(2025, 7, 1)).endDate(LocalDate.of(2025, 7, 5))
                .daysRequested(5).reason("Vacation").build();

        when(leaveRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
        when(userRepository.findById(APPROVER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(EMPLOYEE_ID, LEAVE_TYPE_ID, 2025))
                .thenReturn(Optional.of(LeaveBalance.builder()
                        .daysRemaining(2).totalDaysEntitled(20).year(2025).build()));

        assertThatThrownBy(() -> leaveService.approveLeaveRequest(REQUEST_ID, APPROVER_ID, "Approved"))
                .isInstanceOf(IllegalStateException.class);

        verify(leaveBalanceRepository, never()).deductLeaveDays(any(), any(), any(), any());
        verify(leaveRequestRepository, never()).save(any());
    }

    // 4b ── a concurrent approval consumed the balance: the guarded UPDATE touches 0 rows → throws
    @Test
    void approveLeaveRequest_guardedUpdateAffectsNoRows_throws() {
        LeaveRequest pending = LeaveRequest.builder()
                .status(LeaveStatus.PENDING).employee(employee).leaveType(leaveType)
                .startDate(LocalDate.of(2025, 7, 1)).endDate(LocalDate.of(2025, 7, 5))
                .daysRequested(5).reason("Vacation").build();

        when(leaveRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
        when(userRepository.findById(APPROVER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(EMPLOYEE_ID, LEAVE_TYPE_ID, 2025))
                .thenReturn(Optional.of(LeaveBalance.builder()
                        .daysRemaining(10).totalDaysEntitled(20).year(2025).build()));
        when(leaveBalanceRepository.deductLeaveDays(EMPLOYEE_ID, LEAVE_TYPE_ID, 2025, 5)).thenReturn(0);

        assertThatThrownBy(() -> leaveService.approveLeaveRequest(REQUEST_ID, APPROVER_ID, "Approved"))
                .isInstanceOf(IllegalStateException.class);

        verify(leaveRequestRepository, never()).save(any());
    }

    // 5 ── reject: status set to REJECTED, saved once
    @Test
    void rejectLeaveRequest_statusBecomesRejected() {
        LeaveRequest pending = LeaveRequest.builder()
                .status(LeaveStatus.PENDING)
                .employee(employee)
                .leaveType(leaveType)
                .startDate(LocalDate.of(2025, 8, 1))
                .endDate(LocalDate.of(2025, 8, 3))
                .daysRequested(3)
                .reason("Annual leave")
                .build();

        when(leaveRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
        when(leaveRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        leaveService.rejectLeaveRequest(REQUEST_ID, "Insufficient justification");

        ArgumentCaptor<LeaveRequest> cap = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(leaveRequestRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(LeaveStatus.REJECTED);
    }
}
