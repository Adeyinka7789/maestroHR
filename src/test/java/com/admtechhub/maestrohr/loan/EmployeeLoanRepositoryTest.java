package com.admtechhub.maestrohr.loan;

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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-level proof that {@code idx_employee_loans_one_active} (V48) rejects a second ACTIVE
 * loan row for the same employee — the backstop for the application-layer check in
 * {@link LoanService}, which can't close a race between two concurrent approve/resume calls
 * on its own. Mirrors {@code InvoiceRepositoryTest}'s pattern of hitting the real Postgres
 * configured via {@code .env}; {@code @Transactional} rolls everything back.
 */
@SpringBootTest
@Transactional
class EmployeeLoanRepositoryTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PayGradeRepository payGradeRepository;
    @Autowired private EmployeeLoanRepository loanRepository;
    @Autowired private EntityManager entityManager;

    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private Tenant persistTenant() {
        Tenant tenant = Tenant.builder()
                .companyName("TEST-LOAN " + UUID.randomUUID())
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build();
        return tenantRepository.saveAndFlush(tenant);
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

    private EmployeeLoan loan(Tenant tenant, Employee employee, LoanStatus status) {
        return EmployeeLoan.builder()
                .tenant(tenant)
                .employee(employee)
                .loanAmount(1_200_000L)
                .monthlyInstallment(100_000L)
                .remainingBalance(1_200_000L)
                .repaymentMonths(12)
                .monthsPaid(0)
                .status(status)
                .startDate(LocalDate.now())
                .createdBy("test@test.local")
                .build();
    }

    @Test
    void secondActiveLoanForSameEmployee_violatesUniqueIndex() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        Employee employee = persistEmployee(tenant);

        loanRepository.saveAndFlush(loan(tenant, employee, LoanStatus.ACTIVE)); // first insert: OK

        EmployeeLoan second = loan(tenant, employee, LoanStatus.ACTIVE);
        assertThrows(DataIntegrityViolationException.class,
                () -> loanRepository.saveAndFlush(second),
                "a second ACTIVE loan for the same employee must violate idx_employee_loans_one_active");
    }

    @Test
    void secondNonActiveLoanForSameEmployee_isAllowed() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        Employee employee = persistEmployee(tenant);

        loanRepository.saveAndFlush(loan(tenant, employee, LoanStatus.ACTIVE));
        // A PENDING/PAUSED/COMPLETED/CANCELLED loan for the same employee is unaffected —
        // the partial index only constrains rows WHERE status = 'ACTIVE'.
        EmployeeLoan pending = loanRepository.saveAndFlush(loan(tenant, employee, LoanStatus.PENDING));

        assertNotNull(pending.getId());
    }
}
