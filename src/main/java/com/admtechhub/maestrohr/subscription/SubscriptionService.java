package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.*;
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

    @Transactional
    public Tenant upgradePlan(UUID tenantId, SubscriptionPlan newPlan, PaymentPeriod period) {
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

        log.info("Tenant {} upgraded from {} to {} for {} period",
                tenantId, oldPlan, newPlan, period);

        return saved;
    }

    @Transactional
    public void downgradePlan(UUID tenantId, SubscriptionPlan newPlan) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        tenant.setSubscriptionPlan(newPlan);
        tenantRepository.save(tenant);

        log.info("Tenant {} downgraded to {}", tenantId, newPlan);
    }

    @Transactional
    public void cancelSubscription(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        tenant.setSubscriptionPlan(SubscriptionPlan.FREE_TRIAL);
        tenant.setSubscriptionExpiresAt(OffsetDateTime.now().minusDays(1));
        tenant.setAutoRenew(false);
        tenant.setActive(false);

        tenantRepository.save(tenant);

        log.info("Subscription cancelled for tenant {}", tenantId);
    }

    @Transactional(readOnly = true)
    public boolean hasFeature(UUID tenantId, SubscriptionFeature feature) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        // Check if subscription is still valid
        if (tenant.getSubscriptionExpiresAt().isBefore(OffsetDateTime.now())) {
            return false;
        }

        return tenant.getSubscriptionPlan().hasFeature(feature);
    }

    @Transactional
    public void renewSubscription(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        int monthsToAdd = tenant.getPaymentPeriod() != null ?
                tenant.getPaymentPeriod().getMonths() : 1;

        OffsetDateTime newExpiry = OffsetDateTime.now().plusMonths(monthsToAdd);
        tenant.setSubscriptionExpiresAt(newExpiry);
        tenant.setActive(true);

        tenantRepository.save(tenant);

        log.info("Subscription renewed for tenant {} until {}", tenantId, newExpiry);
    }
}