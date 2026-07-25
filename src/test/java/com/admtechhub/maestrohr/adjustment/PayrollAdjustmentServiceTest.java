package com.admtechhub.maestrohr.adjustment;

import com.admtechhub.maestrohr.adjustment.AdjustmentDTOs.CreateAdjustmentRequest;
import com.admtechhub.maestrohr.adjustment.AdjustmentDTOs.TypeView;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.*;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (real Postgres, rolled back) proof of the adjustments lifecycle: default-type
 * seeding, the four tax-treatment buckets, and the compute → apply → reverse flow the payroll run
 * drives. Mirrors {@code EmployeeLoanRepositoryTest}'s fixture pattern.
 */
@SpringBootTest
@Transactional
class PayrollAdjustmentServiceTest {

    @Autowired private PayrollAdjustmentService service;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PayGradeRepository payGradeRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private EntityManager entityManager;

    private Tenant tenant;
    private Employee employee;

    private static final int MONTH = 7, YEAR = 2026;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.saveAndFlush(Tenant.builder()
                .companyName("TEST-ADJ " + UUID.randomUUID())
                .industry("TEST").companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build());
        bindTenant(tenant.getId());
        TenantContext.setCurrentTenant(tenant.getId().toString()); // the service reads tenant id from here
        employee = persistEmployee(tenant);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void seedsDefaultTypes_onFirstUse() {
        List<TypeView> types = service.listTypes(false);
        assertThat(types).hasSize(8);
        assertThat(types).anyMatch(t -> t.code().equals("PERF_BONUS")
                && t.direction() == AdjustmentDirection.EARNING
                && t.taxTreatment() == AdjustmentTaxTreatment.TAXABLE);
        assertThat(types).anyMatch(t -> t.code().equals("AVC")
                && t.direction() == AdjustmentDirection.DEDUCTION
                && t.taxTreatment() == AdjustmentTaxTreatment.PRE_TAX);
    }

    @Test
    void computeBuckets_routesEachTypeToItsTreatment() {
        log("PERF_BONUS", 5_000_000L);      // taxable earning
        log("TRANSPORT_REIMB", 1_000_000L); // non-taxable earning
        log("AVC", 2_000_000L);             // pre-tax deduction
        log("LATE_FINE", 500_000L);         // post-tax deduction

        Map<UUID, AdjustmentBuckets> map = service.computeBucketsForPeriod(List.of(employee.getId()), MONTH, YEAR);
        AdjustmentBuckets b = map.get(employee.getId());

        assertThat(b).isNotNull();
        assertThat(b.taxableEarnings()).isEqualTo(5_000_000L);
        assertThat(b.nonTaxableEarnings()).isEqualTo(1_000_000L);
        assertThat(b.preTaxDeductions()).isEqualTo(2_000_000L);
        assertThat(b.postTaxDeductions()).isEqualTo(500_000L);
    }

    @Test
    void applyForRun_thenReverse_flipsStatusAndRunLink() {
        UUID adjId = logAndGetId("LATE_FINE", 500_000L);

        PayrollRun run = persistRun();
        PayrollEntry entry = PayrollEntry.builder().employee(employee).build();

        service.applyForRun(run, List.of(entry));
        entityManager.flush();
        entityManager.clear();
        PayrollAdjustment applied = adjustmentRepository.findById(adjId).orElseThrow();
        assertThat(applied.getStatus()).isEqualTo(AdjustmentStatus.APPLIED);
        assertThat(applied.getPayrollRunId()).isEqualTo(run.getId());

        // Reversing the run returns the adjustment to PENDING for a future run.
        service.reverseForRun(run.getId());
        entityManager.flush();
        entityManager.clear();
        PayrollAdjustment reversed = adjustmentRepository.findById(adjId).orElseThrow();
        assertThat(reversed.getStatus()).isEqualTo(AdjustmentStatus.PENDING);
        assertThat(reversed.getPayrollRunId()).isNull();
    }

    @Test
    void appliedAdjustment_isNotRecomputedIntoANewRun() {
        UUID adjId = logAndGetId("PERF_BONUS", 5_000_000L);
        PayrollRun run = persistRun();
        service.applyForRun(run, List.of(PayrollEntry.builder().employee(employee).build()));
        entityManager.flush();

        // Compute now sees no PENDING items for this employee/period → empty buckets.
        Map<UUID, AdjustmentBuckets> map = service.computeBucketsForPeriod(List.of(employee.getId()), MONTH, YEAR);
        assertThat(map.getOrDefault(employee.getId(), AdjustmentBuckets.zero())).isEqualTo(AdjustmentBuckets.zero());
        assertThat(adjustmentRepository.findById(adjId).orElseThrow().getStatus())
                .isEqualTo(AdjustmentStatus.APPLIED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void log(String typeCode, long amountKobo) {
        logAndGetId(typeCode, amountKobo);
    }

    private UUID logAndGetId(String typeCode, long amountKobo) {
        UUID typeId = service.listTypes(false).stream()
                .filter(t -> t.code().equals(typeCode)).findFirst().orElseThrow().id();
        return service.create(new CreateAdjustmentRequest(
                employee.getId(), typeId, amountKobo, MONTH, YEAR, "test"), "hr@test.local").id();
    }

    private PayrollRun persistRun() {
        User initiator = userRepository.saveAndFlush(User.builder()
                .tenantId(tenant.getId())
                .email("hr-" + UUID.randomUUID() + "@test.local")
                .passwordHash("x").role(UserRole.HR_ADMIN).build());
        return payrollRunRepository.saveAndFlush(PayrollRun.builder()
                .tenant(tenant).payrollMonth(MONTH).payrollYear(YEAR)
                .status(PayrollStatus.PENDING_APPROVAL).initiatedBy(initiator).build());
    }

    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private Employee persistEmployee(Tenant t) {
        Department dept = departmentRepository.saveAndFlush(
                Department.builder().tenant(t).name("Engineering").build());
        PayGrade grade = payGradeRepository.saveAndFlush(PayGrade.builder()
                .tenant(t).name("Grade-A").basicSalary(500_000L).housingAllowance(100_000L)
                .transportAllowance(50_000L).otherAllowances(0L).build());
        return employeeRepository.saveAndFlush(Employee.builder()
                .tenant(t).employeeNumber("EMP-" + UUID.randomUUID())
                .firstName("Test").lastName("Employee")
                .email(UUID.randomUUID() + "@test.local").phone("08000000000")
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Gender.MALE)
                .maritalStatus(MaritalStatus.SINGLE).address("1 Test Street")
                .department(dept).payGrade(grade).jobTitle("Engineer")
                .employmentType(EmploymentType.FULL_TIME)
                .employmentStartDate(LocalDate.of(2024, 1, 1))
                .bankName("Test Bank").bankAccountNumber("0123456789").bankAccountName("Test Employee")
                .build());
    }
}
