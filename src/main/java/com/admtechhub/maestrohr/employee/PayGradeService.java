package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayGradeService {

    private final PayGradeRepository payGradeRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public PayGrade create(String name, Long basicSalary, Long housingAllowance,
                           Long transportAllowance, Long otherAllowances) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found: " + tenantId));

        if (payGradeRepository.existsByNameAndTenantId(name, tenantId)) {
            throw new IllegalArgumentException(
                    "Pay grade '" + name + "' already exists"
            );
        }

        PayGrade grade = PayGrade.builder()
                .tenant(tenant)
                .name(name)
                .basicSalary(basicSalary)
                .housingAllowance(housingAllowance)
                .transportAllowance(transportAllowance)
                .otherAllowances(otherAllowances)
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
                              Long otherAllowances) {
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
}