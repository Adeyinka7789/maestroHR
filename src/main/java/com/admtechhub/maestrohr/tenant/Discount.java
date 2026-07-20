package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An admin-managed subscription discount (V58).
 *
 * <p>Like {@link PricingConfig}, this is GLOBAL platform configuration — it carries no
 * {@code @SQLRestriction} and its backing table has no RLS policy. {@link #tenantId} is an
 * optional <em>targeting</em> filter ("only for this customer"), not a tenant-scoping column.
 *
 * <p>Any of {@link #tenantId} / {@link #planName} / {@link #period} left {@code null} means the
 * discount applies to <em>all</em> values on that dimension. Resolution and the "does this apply"
 * / "net price" math live in {@code DiscountService}; this entity is a plain record.
 */
@Entity
@Table(name = "discounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Discount extends BaseEntity {

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    /** Basis points off the base price when {@link #discountType} is PERCENTAGE (2000 = 20%). */
    @Column(name = "percent_bps")
    private Integer percentBps;

    /** Flat kobo off the base price when {@link #discountType} is FIXED. */
    @Column(name = "amount_kobo")
    private Long amountKobo;

    /** Target customer; {@code null} = applies to every customer. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Target plan (e.g. PROFESSIONAL); {@code null} = applies to every plan. */
    @Column(name = "plan_name", length = 50)
    private String planName;

    /** Target billing period (MONTHLY/QUARTERLY/ANNUALLY); {@code null} = applies to every period. */
    @Column(name = "period", length = 20)
    private String period;

    /** Start of validity window; {@code null} = no lower bound. */
    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    /** End of validity window; {@code null} = no upper bound. */
    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
