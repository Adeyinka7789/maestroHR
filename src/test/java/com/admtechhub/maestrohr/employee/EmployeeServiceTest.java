package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.document.OnboardingService;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock EmployeeRepository     employeeRepository;
    @Mock TenantRepository       tenantRepository;
    @Mock DepartmentRepository   departmentRepository;
    @Mock PayGradeRepository     payGradeRepository;
    @Mock PaystackClient         paystackClient;
    @Mock UserRepository         userRepository;
    @Mock PasswordEncoder        passwordEncoder;
    @Mock NotificationService    notificationService;
    @Mock PayrollEntryRepository payrollEntryRepository;
    @Mock LeaveRequestRepository leaveRequestRepository;
    @Mock AttendanceRepository   attendanceRepository;
    @Mock OnboardingService      onboardingService;
    @Mock AuditTrailService      auditTrailService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks EmployeeService employeeService;

    static final UUID   TENANT_ID = UUID.randomUUID();
    static final UUID   EMP_ID    = UUID.randomUUID();
    static final UUID   DEPT_ID   = UUID.randomUUID();
    static final UUID   PG_ID     = UUID.randomUUID();
    static final String EMAIL     = "emp@maestro.com";

    @BeforeEach void bindTenant() { TenantContext.setCurrentTenant(TENANT_ID.toString()); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    Employee active() {
        Employee e = Employee.builder().status(EmployeeStatus.ACTIVE).build();
        e.setId(EMP_ID);
        return e;
    }

    EmployeeRequest minimalRequest() {
        EmployeeRequest r = new EmployeeRequest();
        r.setEmail(EMAIL);
        r.setDepartmentId(DEPT_ID);
        r.setPayGradeId(PG_ID);
        r.setPassword("pass");
        r.setFirstName("John");
        r.setLastName("Doe");
        r.setBankName("GTBank");
        r.setBankAccountNumber("1234567890");
        r.setBankAccountName("John Doe");
        return r;
    }

    void stubTenantDeptGrade() {
        Tenant t = new Tenant();         t.setId(TENANT_ID);
        Department d = new Department(); d.setId(DEPT_ID);
        PayGrade p = PayGrade.builder().basicSalary(50_000L).build(); p.setId(PG_ID);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(t));
        when(departmentRepository.findById(DEPT_ID)).thenReturn(Optional.of(d));
        when(payGradeRepository.findById(PG_ID)).thenReturn(Optional.of(p));
    }

    // 1 ── duplicate email → IAE before any save
    @Test
    void createEmployee_duplicateEmail_throws() {
        stubTenantDeptGrade();
        when(employeeRepository.existsByEmail(EMAIL, TENANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> employeeService.createEmployee(minimalRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(employeeRepository, never()).save(any());
    }

    // 2 ── pre-existing user with same email is linked, not duplicated
    @Test
    void createEmployee_linksExistingUser_whenEmailMatches() {
        stubTenantDeptGrade();
        when(employeeRepository.existsByEmail(EMAIL, TENANT_ID)).thenReturn(false);
        when(employeeRepository.existsByEmployeeNumber(anyString(), any(UUID.class))).thenReturn(false);

        User existingUser = User.builder()
                .email(EMAIL).role(UserRole.EMPLOYEE)
                .passwordHash("hash").failedLoginAttempts(0)
                .build();
        when(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                .thenReturn(Optional.of(existingUser));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        lenient().when(paystackClient.resolveAccount(anyString(), anyString()))
                .thenThrow(new RuntimeException("bank unavailable"));

        employeeService.createEmployee(minimalRequest());

        // no new User row was inserted; the existing user is wired into the employee
        verify(userRepository, never()).save(any());
        ArgumentCaptor<Employee> cap = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(cap.capture());
        assertThat(cap.getValue().getUser()).isSameAs(existingUser);
    }

    // 3 ── terminateEmployee transitions status to TERMINATED
    @Test
    void terminateEmployee_setsStatusTerminated() {
        Employee e = active();
        Tenant mockTenant = new Tenant();
        mockTenant.setId(UUID.randomUUID());
        e.setTenant(mockTenant);
        when(employeeRepository.findByIdAndTenantId(eq(EMP_ID), any(UUID.class))).thenReturn(Optional.of(e));
        when(employeeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        employeeService.terminateEmployee(EMP_ID, LocalDate.now());

        assertThat(e.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        verify(employeeRepository).save(e);
    }

    // 4 ── re-terminating an already-terminated employee → ISE
    @Test
    void terminateEmployee_alreadyTerminated_throws() {
        Employee e = Employee.builder().status(EmployeeStatus.TERMINATED).build();
        e.setId(EMP_ID);
        when(employeeRepository.findByIdAndTenantId(eq(EMP_ID), any(UUID.class))).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> employeeService.terminateEmployee(EMP_ID, LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already terminated");
    }

    // 5 ── hardDelete with payroll history → ISE, physical delete never called
    @Test
    void hardDeleteEmployee_withDependents_throws() {
        when(employeeRepository.findByIdAndTenantId(eq(EMP_ID), any(UUID.class))).thenReturn(Optional.of(active()));
        when(payrollEntryRepository.existsByEmployeeId(eq(EMP_ID), any(UUID.class))).thenReturn(true);

        assertThatThrownBy(() -> employeeService.hardDeleteEmployee(EMP_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payroll");

        verify(employeeRepository, never()).delete(any(Employee.class));
    }

    // 6 ── softDelete with leave records → ISE, save never called
    @Test
    void softDeleteEmployee_withDependents_throws() {
        when(employeeRepository.findByIdAndTenantId(eq(EMP_ID), any(UUID.class))).thenReturn(Optional.of(active()));
        when(payrollEntryRepository.existsByEmployeeId(eq(EMP_ID), any(UUID.class))).thenReturn(false);
        when(leaveRequestRepository.existsByEmployeeId(eq(EMP_ID), any(UUID.class))).thenReturn(true);

        assertThatThrownBy(() -> employeeService.softDeleteEmployee(EMP_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("leave");

        verify(employeeRepository, never()).save(any());
    }

    // 7 ── softDelete with no dependents stamps deletedAt
    @Test
    void softDeleteEmployee_noDependents_setsDeletedAt() {
        Employee e = active();
        when(employeeRepository.findByIdAndTenantId(eq(EMP_ID), any(UUID.class))).thenReturn(Optional.of(e));
        when(payrollEntryRepository.existsByEmployeeId(eq(EMP_ID), any(UUID.class))).thenReturn(false);
        when(leaveRequestRepository.existsByEmployeeId(eq(EMP_ID), any(UUID.class))).thenReturn(false);
        when(attendanceRepository.existsByEmployeeId(eq(EMP_ID), any(UUID.class))).thenReturn(false);
        when(departmentRepository.existsByHeadEmployeeId(EMP_ID.toString())).thenReturn(false);
        when(employeeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        employeeService.softDeleteEmployee(EMP_ID);

        assertThat(e.getDeletedAt()).isNotNull();
    }

    // 8 ── RLS hides a row from the wrong tenant → IAE as if not found
    @Test
    void getEmployeeById_wrongTenant_throws() {
        when(employeeRepository.findByIdAndTenantId(eq(EMP_ID), any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employeeService.getEmployeeById(EMP_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }
}
