package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for {@link PayGradeRepository#findFilteredWithEmployeeCount} against the
 * real Postgres configured via {@code .env}. Confirms the JPQL constructor projection
 * parses and runs (LEFT JOIN + COUNT + GROUP BY, the null-safe CAST(:search AS string)
 * guard, and the gross-salary ORDER BY) and that {@code employeeCount} reflects the
 * {@code Employee.payGrade} assignment. Mirrors the setup of the department/subscription
 * repository tests; {@code @Transactional} rolls everything back.
 */
@SpringBootTest
@Transactional
class PayGradeRepositoryFilterTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PayGradeRepository payGradeRepository;
    @Autowired private EntityManager entityManager;

    /** Bind app.current_tenant for the rest of this (test) transaction. */
    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private Tenant persistTenant() {
        Tenant tenant = Tenant.builder()
                .companyName("TEST-PAYGRADE " + UUID.randomUUID())
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build();
        return tenantRepository.saveAndFlush(tenant);
    }

    private PayGrade persistGrade(Tenant tenant, String name, long basic) {
        PayGrade grade = PayGrade.builder()
                .tenant(tenant)
                .name(name)
                .basicSalary(basic)
                .housingAllowance(10_000L)
                .transportAllowance(5_000L)
                .otherAllowances(0L)
                .build();
        return payGradeRepository.saveAndFlush(grade);
    }

    private Department persistDepartment(Tenant tenant) {
        Department dept = Department.builder().tenant(tenant).name("Engineering").build();
        return departmentRepository.saveAndFlush(dept);
    }

    private void persistEmployee(Tenant tenant, Department dept, PayGrade grade) {
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
    }

    @Test
    void findFiltered_nullSearch_returnsGradesWithEmployeeCount() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());

        Department dept = persistDepartment(tenant);
        PayGrade senior = persistGrade(tenant, "Senior", 500_000L);   // highest gross
        PayGrade junior = persistGrade(tenant, "Junior", 100_000L);   // lowest gross

        // Two employees on Senior, none on Junior.
        persistEmployee(tenant, dept, senior);
        persistEmployee(tenant, dept, senior);
        entityManager.clear(); // force the read to hit the DB

        List<PayGradeListRow> rows =
                payGradeRepository.findFilteredWithEmployeeCount(tenant.getId(), null);

        assertEquals(2, rows.size(), "null search must return every active grade");

        // ORDER BY gross DESC → Senior first.
        assertEquals("Senior", rows.get(0).getName(), "highest-gross grade must sort first");
        assertEquals("Junior", rows.get(1).getName());

        Map<String, PayGradeListRow> byName =
                rows.stream().collect(Collectors.toMap(PayGradeListRow::getName, Function.identity()));
        assertEquals(2L, byName.get("Senior").getEmployeeCount(), "Senior has two assigned employees");
        assertEquals(0L, byName.get("Junior").getEmployeeCount(),
                "a grade with no employees must still appear with count 0 (LEFT JOIN)");
    }

    @Test
    void findFiltered_withSearch_matchesCaseInsensitiveSubstring() {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        persistGrade(tenant, "Senior Engineer", 500_000L);
        persistGrade(tenant, "Junior Analyst", 100_000L);
        entityManager.clear();

        List<PayGradeListRow> rows =
                payGradeRepository.findFilteredWithEmployeeCount(tenant.getId(), "senior");

        assertEquals(1, rows.size(), "search must filter to the matching grade only");
        assertTrue(rows.get(0).getName().toLowerCase().contains("senior"));
    }
}
