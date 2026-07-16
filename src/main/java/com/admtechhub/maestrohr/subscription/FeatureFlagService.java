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
     *
     * <p><b>Null tenant</b> (no request context / unbound thread): the flag gate can still
     * resolve a global flag (override and rollout layers are simply skipped), but entitlement
     * is a per-tenant question that cannot be answered, so a null tenant is never entitled and
     * this returns {@code false}. Use {@link PlatformFlagService#isEnabled(String)} directly for
     * a pure global-flag check with no entitlement.
     */
    public boolean isEnabled(SubscriptionFeature feature) {
        UUID tenantId = currentTenantId();
        String planName = tenantId != null ? subscriptionService.getPlanName(tenantId) : null;

        if (!platformFlagService.isEnabledForTenant(feature.name(), tenantId, planName)) {
            return false;
        }
        return tenantId != null && subscriptionService.hasFeature(tenantId, feature);
    }

    /**
     * Enforce the gate, distinguishing <i>why</i> access is denied so callers surface the right
     * outcome (see {@link FeatureAccessException}):
     * <ul>
     *   <li>platform flag off (kill switch / rollout / unregistered) → {@link FeatureDisabledException}
     *       (HTTP 404) — the feature is unavailable regardless of plan;</li>
     *   <li>flag on but the tenant's plan lacks the feature (or no tenant is bound) →
     *       {@link FeatureNotAvailableException} (HTTP 402) — upgrade is the remedy.</li>
     * </ul>
     */
    public void requireFeature(SubscriptionFeature feature) {
        UUID tenantId = currentTenantId();
        String planName = tenantId != null ? subscriptionService.getPlanName(tenantId) : null;

        if (!platformFlagService.isEnabledForTenant(feature.name(), tenantId, planName)) {
            throw new FeatureDisabledException(feature);
        }
        if (tenantId == null || !subscriptionService.hasFeature(tenantId, feature)) {
            throw new FeatureNotAvailableException(feature);
        }
    }

    /** The current tenant from {@link TenantContext} as a UUID, or {@code null} when unbound/blank. */
    private UUID currentTenantId() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        return (tenantIdStr != null && !tenantIdStr.isBlank())
                ? UUID.fromString(tenantIdStr) : null;
    }
}