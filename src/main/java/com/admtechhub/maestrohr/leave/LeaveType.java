package com.admtechhub.maestrohr.leave;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_types")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "max_days_per_year", nullable = false)
    private Integer maxDaysPerYear;

    @Column(name = "is_paid", nullable = false)
    @Builder.Default
    private Boolean isPaid = true;

    @Column(name = "requires_approval", nullable = false)
    @Builder.Default
    private Boolean requiresApproval = true;

    @Column(name = "carry_over_allowed", nullable = false)
    @Builder.Default
    private Boolean carryOverAllowed = false;

    @Column(name = "max_carry_over_days")
    @Builder.Default
    private Integer maxCarryOverDays = 0;
}