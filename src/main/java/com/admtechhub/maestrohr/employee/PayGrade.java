package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pay_grades")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PayGrade extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "subscriptionPlan", "subscriptionExpiresAt", "isActive"})
    private Tenant tenant;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "basic_salary", nullable = false)
    private Long basicSalary;  // in kobo

    @Column(name = "housing_allowance", nullable = false)
    @Builder.Default
    private Long housingAllowance = 0L;

    @Column(name = "transport_allowance", nullable = false)
    @Builder.Default
    private Long transportAllowance = 0L;

    @Column(name = "other_allowances", nullable = false)
    @Builder.Default
    private Long otherAllowances = 0L;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    public Long getGrossSalary() {
        return basicSalary + housingAllowance + transportAllowance + otherAllowances;
    }
}