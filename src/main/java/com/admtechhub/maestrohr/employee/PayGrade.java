package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "pay_grades")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayGrade extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "basic_salary", nullable = false)
    private Long basicSalary;

    @Column(name = "housing_allowance", nullable = false)
    private Long housingAllowance;

    @Column(name = "transport_allowance", nullable = false)
    private Long transportAllowance;

    @Column(name = "other_allowances", nullable = false)
    private Long otherAllowances;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    public Long getGrossSalary() {
        return basicSalary + housingAllowance + transportAllowance + otherAllowances;
    }
}