package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.attendance.AttendancePolicy;
import com.admtechhub.maestrohr.attendance.AttendancePolicyRepository;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.loan.LoanPolicy;
import com.admtechhub.maestrohr.loan.LoanPolicyRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayGradeService {

    private final PayGradeRepository payGradeRepository;
    private final TenantRepository tenantRepository;
    private final EmployeeRepository employeeRepository;  // soft-delete guard: live employees still on this grade
    private final LoanPolicyRepository loanPolicyRepository;
    private final AttendancePolicyRepository attendancePolicyRepository;

    @Transactional
    public PayGrade create(String name, Long basicSalary, Long housingAllowance,
                           Long transportAllowance, Long otherAllowances, UUID loanPolicyId,
                           UUID attendancePolicyId) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found: " + tenantId));

        if (payGradeRepository.existsByNameAndTenantId(name, tenantId)) {
            throw new IllegalArgumentException(
                    "Pay grade '" + name + "' already exists"
            );
        }

        LoanPolicy loanPolicy = loanPolicyId != null
                ? loanPolicyRepository.findById(loanPolicyId).orElse(null)
                : null;
        AttendancePolicy attendancePolicy = attendancePolicyId != null
                ? attendancePolicyRepository.findById(attendancePolicyId).orElse(null)
                : null;

        PayGrade grade = PayGrade.builder()
                .tenant(tenant)
                .name(name)
                .basicSalary(basicSalary)
                .housingAllowance(housingAllowance)
                .transportAllowance(transportAllowance)
                .otherAllowances(otherAllowances)
                .loanPolicy(loanPolicy)
                .attendancePolicy(attendancePolicy)
                .build();

        return payGradeRepository.save(grade);
    }

    @Transactional(readOnly = true)
    public List<PayGrade> findAllActive() {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        List<PayGrade> grades = payGradeRepository.findAllByTenantIdAndIsActive(tenantId, true);

        // Initialize lazy-loaded tenant for each grade
        grades.forEach(grade -> {
            if (grade.getTenant() != null) {
                grade.getTenant().getId();
                grade.getTenant().getCompanyName();
            }
        });

        return grades;
    }

    @Transactional(readOnly = true)
    public PayGrade findById(UUID id) {
        PayGrade grade = payGradeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pay grade not found"));

        // Initialize lazy-loaded tenant
        if (grade.getTenant() != null) {
            grade.getTenant().getId();
        }

        return grade;
    }

    @Transactional
    public PayGradeDTO update(UUID id, String name, Long basicSalary,
                              Long housingAllowance, Long transportAllowance,
                              Long otherAllowances, UUID loanPolicyId,
                              UUID attendancePolicyId) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        if (payGradeRepository.existsByNameAndTenantIdAndIdNot(name, tenantId, id)) {
            throw new IllegalArgumentException("Pay grade '" + name + "' already exists");
        }
        PayGrade grade = findById(id);
        grade.setName(name);
        grade.setBasicSalary(basicSalary);
        grade.setHousingAllowance(housingAllowance);
        grade.setTransportAllowance(transportAllowance);
        grade.setOtherAllowances(otherAllowances);
        LoanPolicy loanPolicy = loanPolicyId != null
                ? loanPolicyRepository.findById(loanPolicyId).orElse(null)
                : null;
        grade.setLoanPolicy(loanPolicy);
        AttendancePolicy attendancePolicy = attendancePolicyId != null
                ? attendancePolicyRepository.findById(attendancePolicyId).orElse(null)
                : null;
        grade.setAttendancePolicy(attendancePolicy);
        PayGrade saved = payGradeRepository.save(grade);
        return toDto(saved);
    }

    // Add this method in PayGradeService
    private PayGradeDTO toDto(PayGrade grade) {
        return new PayGradeDTO(
                grade.getId(),
                grade.getName(),
                grade.getBasicSalary(),
                grade.getHousingAllowance(),
                grade.getTransportAllowance(),
                grade.getOtherAllowances(),
                grade.getIsActive(),
                grade.getGrossSalary()
        );
    }

    @Transactional
    public void deactivate(UUID id) {
        PayGrade grade = findById(id);
        grade.setIsActive(false);
        payGradeRepository.save(grade);
    }

    /**
     * Soft-delete a pay grade: stamp {@code deleted_at} so the {@code @SQLRestriction} hides it
     * from every scoped read, after which it sits in the 90-day trash until the cleanup job purges
     * it (or a super-admin restores it). Distinct from {@link #deactivate}, which keeps the grade in
     * the table but flagged inactive. Blocked when any live employee is still on this grade, so a
     * grade can never vanish out from under an employee that references it.
     */
    @Transactional
    public void delete(UUID id) {
        PayGrade grade = findById(id);
        if (employeeRepository.existsByPayGradeId(id)) {
            throw new IllegalStateException(
                    "This pay grade is assigned to employees and cannot be deleted. Reassign them to another grade first.");
        }
        grade.setDeletedAt(OffsetDateTime.now());
        payGradeRepository.save(grade);
        log.info("Soft-deleted pay grade: {}", id);
    }
}