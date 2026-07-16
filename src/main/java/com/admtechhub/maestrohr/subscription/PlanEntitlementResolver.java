package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default {@link EntitlementResolver}: delegates to {@link SubscriptionService#hasFeature}, which
 * checks the tenant's plan matrix plus subscription lifecycle/expiry. Thin by design — it exists
 * to name the entitlement seam and keep {@link FeatureAccessService} independent of the concrete
 * subscription service.
 */
@Component
@RequiredArgsConstructor
public class PlanEntitlementResolver implements EntitlementResolver {

    private final SubscriptionService subscriptionService;

    @Override
    public boolean includes(UUID tenantId, SubscriptionFeature feature) {
        return subscriptionService.hasFeature(tenantId, feature);
    }
}
