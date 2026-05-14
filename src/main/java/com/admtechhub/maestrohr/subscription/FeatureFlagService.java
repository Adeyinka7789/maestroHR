package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService {

    private final SubscriptionService subscriptionService;

    public boolean isEnabled(SubscriptionFeature feature) {
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            return false;
        }
        UUID tenantId = UUID.fromString(tenantIdStr);
        return subscriptionService.hasFeature(tenantId, feature);
    }

    public void requireFeature(SubscriptionFeature feature) {
        if (!isEnabled(feature)) {
            throw new IllegalStateException(
                    "This feature is not available in your current subscription plan. " +
                            "Please upgrade to access this feature."
            );
        }
    }
}