package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final TenantRepository tenantRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final PricingService pricingService;
    private final EntityManager entityManager;

    @Transactional
    public Tenant upgradePlan(UUID tenantId, SubscriptionPlan newPlan, PaymentPeriod period) {
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        SubscriptionPlan oldPlan = tenant.getSubscriptionPlan();

        tenant.setSubscriptionPlan(newPlan);
        tenant.setPaymentPeriod(period);

        // Calculate new expiry date based on payment period
        OffsetDateTime now = OffsetDateTime.now();
        int monthsToAdd = period.getMonths();
        tenant.setSubscriptionExpiresAt(now.plusMonths(monthsToAdd));

        Tenant saved = tenantRepository.save(tenant);
        syncSubscription(saved, SubscriptionStatus.ACTIVE);

        log.info("Tenant {} upgraded from {} to {} for {} period",
                tenantId, oldPlan, newPlan, period);

        return saved;
    }

    @Transactional
    public void downgradePlan(UUID tenantId, SubscriptionPlan newPlan) {
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        tenant.setSubscriptionPlan(newPlan);
        Tenant saved = tenantRepository.save(tenant);
        syncSubscription(saved, SubscriptionStatus.ACTIVE);

        log.info("Tenant {} downgraded to {}", tenantId, newPlan);
    }

    @Transactional
    public void cancelSubscription(UUID tenantId) {
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        tenant.setSubscriptionPlan(SubscriptionPlan.FREE_TRIAL);
        tenant.setSubscriptionExpiresAt(OffsetDateTime.now().minusDays(1));
        tenant.setAutoRenew(false);
        tenant.setActive(false);

        Tenant saved = tenantRepository.save(tenant);
        syncSubscription(saved, SubscriptionStatus.CANCELLED);

        log.info("Subscription cancelled for tenant {}", tenantId);
    }

    @Transactional(readOnly = true)
    public boolean hasFeature(UUID tenantId, SubscriptionFeature feature) {
        bindTenantSession(tenantId);
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElse(null);

        // No subscription record → fail closed.
        if (subscription == null) {
            return false;
        }

        // Lifecycle states that grant no access.
        SubscriptionStatus status = subscription.getStatus();
        if (status == SubscriptionStatus.CANCELLED
                || status == SubscriptionStatus.EXPIRED
                || status == SubscriptionStatus.PAST_DUE) {
            return false;
        }

        // Expired by date (covers TRIALING/ACTIVE whose period has lapsed).
        OffsetDateTime periodEnd = subscription.getCurrentPeriodEnd();
        if (periodEnd == null || periodEnd.isBefore(OffsetDateTime.now())) {
            return false;
        }

        SubscriptionPlan plan = subscription.getPlan();
        return plan != null && plan.hasFeature(feature);
    }

    @Transactional
    public void renewSubscription(UUID tenantId) {
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        int monthsToAdd = tenant.getPaymentPeriod() != null ?
                tenant.getPaymentPeriod().getMonths() : 1;

        OffsetDateTime newExpiry = OffsetDateTime.now().plusMonths(monthsToAdd);
        tenant.setSubscriptionExpiresAt(newExpiry);
        tenant.setActive(true);

        Tenant saved = tenantRepository.save(tenant);
        syncSubscription(saved, SubscriptionStatus.ACTIVE);

        log.info("Subscription renewed for tenant {} until {}", tenantId, newExpiry);
    }

    /**
     * Upsert the tenant's {@link TenantSubscription} to mirror the tenant's current
     * plan/period/expiry, applying the given lifecycle status. Call this from any flow
     * that changes subscription state (including Paystack webhooks) so feature access
     * via {@link #hasFeature} stays correct.
     *
     * <p>The caller must already have bound the tenant session (this method does, via
     * {@link #bindTenantSession}) so the scoped {@code @SQLRestriction} lookup resolves.
     */
    @Transactional
    public void syncSubscriptionState(UUID tenantId, SubscriptionStatus status) {
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        syncSubscription(tenant, status);
    }

    private TenantSubscription syncSubscription(Tenant tenant, SubscriptionStatus status) {
        TenantSubscription subscription = tenantSubscriptionRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> TenantSubscription.builder()
                        .tenant(tenant)
                        .currentPeriodStart(OffsetDateTime.now())
                        .build());

        subscription.setPlan(tenant.getSubscriptionPlan());
        subscription.setPeriod(tenant.getPaymentPeriod());
        subscription.setStatus(status);
        subscription.setCurrentPeriodEnd(tenant.getSubscriptionExpiresAt());
        subscription.setAutoRenew(tenant.isAutoRenew());
        subscription.setPaystackSubscriptionCode(tenant.getPaystackSubscriptionCode());
        subscription.setPaystackCustomerCode(tenant.getPaystackCustomerCode());
        subscription.setPriceKobo(resolvePriceKobo(tenant.getSubscriptionPlan(), tenant.getPaymentPeriod()));

        return tenantSubscriptionRepository.save(subscription);
    }

    private long resolvePriceKobo(SubscriptionPlan plan, PaymentPeriod period) {
        String periodName = (period != null ? period.name() : PaymentPeriod.MONTHLY.name());
        return pricingService.getPrice(plan.name(), periodName);
    }

    /**
     * Bind {@code app.current_tenant} for the rest of this transaction so that
     * {@code @SQLRestriction} on the billing entities resolves to the target tenant —
     * even when the ambient request context is a different tenant (SUPER_ADMIN acting
     * cross-tenant) or no tenant at all (Paystack webhook). Transaction-local, so it
     * reverts on commit/rollback.
     */
    private void bindTenantSession(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }
}
