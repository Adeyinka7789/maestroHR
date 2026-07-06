package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmploymentType;
import com.admtechhub.maestrohr.employee.Gender;
import com.admtechhub.maestrohr.employee.MaritalStatus;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.employee.PayGradeRepository;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-level proof for V53:
 *   1. idx_payroll_runs_one_active_period rejects a second non-REJECTED/REVERSED
 *      payroll_runs row for the same (tenant, month, year) — the backstop for the race
 *      PayrollRunService.initiatePayroll's existsBy... pre-check can't close on its own —
 *      but allows a fresh run once the conflicting one is REJECTED or REVERSED.
 *   2. chk_payroll_status / chk_transfer_status now accept every status value the Java
 *      code actually writes, including the three newly-added values per column.
 *
 * Mirrors EmployeeLoanRepositoryTest's pattern of hitting the real Postgres configured via
 * .env; @Transactional rolls everything back.
 */
@SpringBootTest
@Transactional
class PayrollRunUniquenessAndStatusConstraintTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PayGradeRepository payGradeRepository;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private PayrollEntryRepository payrollEntryRepository;
    @Autowired private EntityManager entityManager;

    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private Tenant persistTenant() {
        Tenant tenant = Tenant.builder()
                .companyName("TEST-PAYROLL " + UUID.randomUUID())
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build();
        return tenantRepository.saveAndFlush(tenant);
    }

    private User persistUser(Tenant tenant) {
        User user = User.builder()
                .tenantId(tenant.getId())
                .email(UUID.randomUUID() + "@test.local")
                .passwordHash("hash")
                .role(UserRole.HR_ADMIN)
                .build();
        return userRepository.saveAndFlush(user);
    }

    private Employee persistEmployee(Tenant tenant) {
        Department dept = Department.builder().tenant(tenant).name("Engineering").build();
        departmentRepository.saveAndFlush(dept);

        PayGrade grade = PayGrade.builder()
                .tenant(tenant)
                .name("Grade-A")
                .basicSalary(500_000L)
                .housingAllowance(100_000L)
                .transportAllowance(50_000L)
                .otherAllowances(0L)
                .build();
        payGradeRepository.saveAndFlush(grade);

        Employee e = Employee.builder()
                .tenant(tenant)
                .employeeNumber("EMP-" + UUID.randomUUID())
                .firstName("Test").lastName("Employee")
                .email(UUID.randomUUID() + "@test.local")
                .phone("08000000000")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender(Gender.MALE)
                .maritalStatus(MaritalStatus.SINGLE)
                .address("1 Test Street")
                .department(dept)
                .payGrade(grade)
                .jobTitle("Engineer")
                .employmentType(EmploymentType.FULL_TIME)
                .employmentStartDate(LocalDate.of(2024, 1, 1))
                .bankName("Test Bank")
                .bankAccountNumber("0123456789")
                .bankAccountName("Test Employee")
                .build();
        entityManager.persist(e);
        entityManager.flush();
        return e;
    }

    private PayrollRun payrollRun(Tenant tenant, User initiator, int month, int year, PayrollStatus status) {
        return PayrollRun.builder()
                .tenant(tenant)
                .payrollMonth(month)
                .payrollYear(year)
                .status(status)
                .initiatedBy(initiator)
                .build();
    }

    private PayrollEntry payrollEntry(Tenant tenant, PayrollRun run, Employee employee, TransferStatus transferStatus) {
        return PayrollEntry.builder()
                .tenant(tenant)
                .payrollRun(run)
                .employee(employee)
                .basicSalary(500_000L)
                .housingAllowance(100_000L)
                .transportAllowance(50_000L)
                .otherAllowances(0L)
                .grossSalary(650_000L)
                .pensionEmployee(52_000L)
                .pensionEmployer(65_000L)
                .nhfDeduction(12_500L)
                .payeTax(0L)
                .netSalary(585_500L)
                .daysWorked(22)
                .workingDays(22)
                .transferStatus(transferStatus)
                .build();
    }

    // ── FIX 2: partial unique index on (tenant_id, payroll_month, payroll_year) ─────────────

    @Test
    void secondNonTerminalPayrollRunForSamePeriod_violatesUniqueIndex() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);

        payrollRunRepository.saveAndFlush(payrollRun(tenant, initiator, 6, 2027, PayrollStatus.DRAFT));

        PayrollRun second = payrollRun(tenant, initiator, 6, 2027, PayrollStatus.PENDING_APPROVAL);
        assertThrows(DataIntegrityViolationException.class,
                () -> payrollRunRepository.saveAndFlush(second),
                "a second non-REJECTED/REVERSED run for the same tenant/month/year must violate "
                        + "idx_payroll_runs_one_active_period");
    }

    @Test
    void freshPayrollRunForSamePeriod_isAllowedAfterFirstIsRejected() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);

        payrollRunRepository.saveAndFlush(payrollRun(tenant, initiator, 7, 2027, PayrollStatus.REJECTED));

        // A REJECTED (or REVERSED) run for the same period is excluded from the partial index,
        // so a fresh run for that period must be allowed to supersede it.
        PayrollRun fresh = payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 7, 2027, PayrollStatus.DRAFT));

        assertNotNull(fresh.getId());
    }

    // ── FIX 1: chk_payroll_status now accepts every PayrollStatus value ─────────────────────

    @Test
    void payrollStatus_FAILED_persistsWithoutConstraintViolation() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);

        assertDoesNotThrow(() -> payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 1, 2027, PayrollStatus.FAILED)));
    }

    @Test
    void payrollStatus_DISBURSING_UNKNOWN_persistsWithoutConstraintViolation() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);

        assertDoesNotThrow(() -> payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 2, 2027, PayrollStatus.DISBURSING_UNKNOWN)));
    }

    @Test
    void payrollStatus_REVERSED_persistsWithoutConstraintViolation() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);

        assertDoesNotThrow(() -> payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 3, 2027, PayrollStatus.REVERSED)));
    }

    // ── FIX 1: chk_transfer_status now accepts every TransferStatus value the code writes ───

    @Test
    void transferStatus_DISBURSING_persistsWithoutConstraintViolation() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);
        Employee employee = persistEmployee(tenant);
        PayrollRun run = payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 4, 2027, PayrollStatus.DRAFT));

        assertDoesNotThrow(() -> payrollEntryRepository.saveAndFlush(
                payrollEntry(tenant, run, employee, TransferStatus.DISBURSING)));
    }

    @Test
    void transferStatus_PAID_persistsWithoutConstraintViolation() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);
        Employee employee = persistEmployee(tenant);
        PayrollRun run = payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 5, 2027, PayrollStatus.APPROVED));

        assertDoesNotThrow(() -> payrollEntryRepository.saveAndFlush(
                payrollEntry(tenant, run, employee, TransferStatus.PAID)));
    }

    @Test
    void transferStatus_REVERSED_persistsWithoutConstraintViolation() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        User initiator = persistUser(tenant);
        Employee employee = persistEmployee(tenant);
        PayrollRun run = payrollRunRepository.saveAndFlush(
                payrollRun(tenant, initiator, 8, 2027, PayrollStatus.REVERSED));

        assertDoesNotThrow(() -> payrollEntryRepository.saveAndFlush(
                payrollEntry(tenant, run, employee, TransferStatus.REVERSED)));
    }
}
