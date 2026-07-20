package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.subscription.TenantSubscription;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

/**
 * A billing record for one payment attempt against a tenant's subscription.
 *
 * <p>{@link #paystackReference} is the idempotency key: it is {@code UNIQUE NOT NULL}
 * so a Paystack webhook (which can be delivered more than once) can be de-duplicated
 * by attempting to look up / insert on this reference.
 *
 * <p>Plan and period are snapshotted at creation so the invoice is an immutable
 * record of what was bought, independent of later plan or pricing changes.
 *
 * <p>Tenant-scoped via {@code @SQLRestriction}, like every other tenant-owned entity.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class Invoice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    @JsonIgnoreProperties({"tenant", "createdAt", "updatedAt"})
    private TenantSubscription subscription;

    /** Idempotency key — the Paystack transaction reference. Unique, never null. */
    @Column(name = "paystack_reference", nullable = false, unique = true)
    private String paystackReference;

    /** The NET amount actually charged (after any discount). */
    @Column(name = "amount_kobo", nullable = false)
    private Long amountKobo;

    /** Amount taken off the base price by an applied discount; 0 when none. */
    @Column(name = "discount_kobo", nullable = false)
    @Builder.Default
    private Long discountKobo = 0L;

    /** Human-readable label of the applied discount (e.g. "Launch promo – 20% off"); null when none. */
    @Column(name = "discount_label", length = 160)
    private String discountLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 50)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", length = 20)
    private PaymentPeriod period;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "failure_reason")
    private String failureReason;
}
