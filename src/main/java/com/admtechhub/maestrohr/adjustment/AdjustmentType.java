package com.admtechhub.maestrohr.adjustment;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A tenant-defined kind of payroll adjustment (e.g. "Performance Bonus", "Late Fine",
 * "Voluntary Pension"). Carries the {@link AdjustmentDirection} and {@link AdjustmentTaxTreatment}
 * that tell the payroll engine how any adjustment of this type hits the calculation. System types
 * ({@link #isSystem}) are the seeded defaults; their direction/tax treatment must not be edited.
 */
@Entity
@Table(name = "adjustment_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class AdjustmentType extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "direction", nullable = false)
    @Enumerated(EnumType.STRING)
    private AdjustmentDirection direction;

    @Column(name = "tax_treatment", nullable = false)
    @Enumerated(EnumType.STRING)
    private AdjustmentTaxTreatment taxTreatment;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private boolean system = false;

    /** Guard the direction/tax-treatment pairing before persisting (mirrors the V61 DB constraint). */
    public void validate() {
        boolean ok = switch (direction) {
            case EARNING -> taxTreatment == AdjustmentTaxTreatment.TAXABLE
                    || taxTreatment == AdjustmentTaxTreatment.NON_TAXABLE;
            case DEDUCTION -> taxTreatment == AdjustmentTaxTreatment.PRE_TAX
                    || taxTreatment == AdjustmentTaxTreatment.POST_TAX;
        };
        if (!ok) {
            throw new IllegalArgumentException(
                    direction + " adjustments cannot have tax treatment " + taxTreatment);
        }
    }
}
