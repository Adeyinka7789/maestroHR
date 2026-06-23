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
    private final PlatformFlagService platformFlagService;

    /**
     * A feature is available iff the global platform flag is on AND the tenant's plan
     * includes it. The global flag is a platform-wide kill switch keyed by the feature's
     * enum name; an absent flag defaults to enabled, so features without a flag behave
     * exactly as before. The global check is evaluated first (and short-circuits) because it
     * is tenant-independent — a feature switched off globally is unavailable to everyone.
     */
    public boolean isEnabled(SubscriptionFeature feature) {
        if (!platformFlagService.isEnabled(feature.name())) {
            return false;
        }
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            return false;
        }
        UUID tenantId = UUID.fromString(tenantIdStr);
        return subscriptionService.hasFeature(tenantId, feature);
    }

    public void requireFeature(SubscriptionFeature feature) {
        if (!isEnabled(feature)) {
            throw new FeatureNotAvailableException(feature);
        }
    }
}