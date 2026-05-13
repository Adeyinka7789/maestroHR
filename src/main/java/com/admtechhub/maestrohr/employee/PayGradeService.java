package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
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

    @Transactional
    public PayGrade create(String name, Long basicSalary, Long housingAllowance,
                           Long transportAllowance, Long otherAllowances) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());

        if (payGradeRepository.existsByNameAndTenantId(name, tenantId)) {
            throw new IllegalArgumentException(
                    "Pay grade '" + name + "' already exists"
            );
        }

        PayGrade grade = PayGrade.builder()
                .tenantId(tenantId)
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
        return payGradeRepository.findAllByTenantIdAndIsActive(tenantId, true);
    }

    @Transactional(readOnly = true)
    public PayGrade findById(UUID id) {
        return payGradeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Pay grade not found"
                ));
    }

    @Transactional
    public PayGrade update(UUID id, String name, Long basicSalary,
                           Long housingAllowance, Long transportAllowance,
                           Long otherAllowances) {
        PayGrade grade = findById(id);
        grade.setName(name);
        grade.setBasicSalary(basicSalary);
        grade.setHousingAllowance(housingAllowance);
        grade.setTransportAllowance(transportAllowance);
        grade.setOtherAllowances(otherAllowances);
        return payGradeRepository.save(grade);
    }

    @Transactional
    public void deactivate(UUID id) {
        PayGrade grade = findById(id);
        grade.setActive(false);
        payGradeRepository.save(grade);
    }
}