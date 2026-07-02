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
     * A feature is available iff the layered platform flag resolves to on AND the tenant's
     * plan includes it. The layered flag check (tenant override → plan override → global kill
     * switch → rollout percentage → global default, see
     * {@link PlatformFlagService#isEnabledForTenant}) is evaluated first and short-circuits,
     * since a flag switched off for this tenant is unavailable regardless of plan.
     */
    public boolean isEnabled(SubscriptionFeature feature) {
        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = (tenantIdStr != null && !tenantIdStr.isBlank())
                ? UUID.fromString(tenantIdStr) : null;
        String planName = tenantId != null ? subscriptionService.getPlanName(tenantId) : null;

        if (!platformFlagService.isEnabledForTenant(feature.name(), tenantId, planName)) {
            return false;
        }
        if (tenantId == null) {
            return false;
        }
        return subscriptionService.hasFeature(tenantId, feature);
    }

    public void requireFeature(SubscriptionFeature feature) {
        if (!isEnabled(feature)) {
            throw new FeatureNotAvailableException(feature);
        }
    }
}