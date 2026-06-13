package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

/**
 * The current subscription state for a tenant — the single source of truth for
 * plan, billing period, lifecycle status and expiry. One row per tenant.
 *
 * <p>Tenant-scoped: {@code @SQLRestriction} filters by the {@code app.current_tenant}
 * session variable (set per connection by {@code DataSourceConfig}), matching the
 * isolation pattern used by every other tenant-owned entity. {@code BaseEntity}
 * does not carry the restriction, so it is declared explicitly here.
 */
@Entity
@Table(name = "tenant_subscriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class TenantSubscription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false, unique = true)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 50)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", length = 20)
    private PaymentPeriod period;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "current_period_start")
    private OffsetDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private OffsetDateTime currentPeriodEnd;

    @Column(name = "auto_renew", nullable = false)
    @Builder.Default
    private boolean autoRenew = true;

    @Column(name = "paystack_subscription_code")
    private String paystackSubscriptionCode;

    @Column(name = "paystack_customer_code")
    private String paystackCustomerCode;

    /**
     * Price locked in when this subscription period was purchased, in kobo.
     * Snapshotting here means later edits to {@code pricing_config} never
     * retroactively change what an active tenant is billed.
     */
    @Column(name = "price_kobo", nullable = false)
    @Builder.Default
    private Long priceKobo = 0L;
}
