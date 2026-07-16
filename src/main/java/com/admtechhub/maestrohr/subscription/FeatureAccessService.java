package com.admtechhub.maestrohr.subscription;
import com.admtechhub.maestrohr.flags.*;

import com.admtechhub.maestrohr.flags.FlagContextResolver.FlagContext;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The single place the two independent gates on a feature meet: the <b>flag</b> (is the feature
 * switched on for this tenant — kill switch, per-tenant/plan override, rollout — via
 * {@link PlatformFlagService}) and the <b>entitlement</b> (does the tenant's plan include it — via
 * {@link EntitlementResolver}). A feature is available iff both pass.
 *
 * <p>Composition-only: it holds no resolution logic of its own, just wires the extractable flag
 * engine to the application's billing model. The targeting context (tenant + plan) is resolved
 * once per call through {@link FlagContextResolver} and reused for both gates.
 *
 * <p>Replaces the former fused {@code FeatureFlagService}, whose flag and entitlement concerns are
 * now cleanly separated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureAccessService {

    private final PlatformFlagService platformFlagService;
    private final FlagContextResolver contextResolver;
    private final EntitlementResolver entitlementResolver;

    /**
     * Whether {@code feature} is available to the current tenant: the platform flag resolves on
     * AND the tenant's plan includes it. A null tenant (no context) is never entitled, so returns
     * {@code false} even when the global flag is on.
     */
    public boolean isAvailable(SubscriptionFeature feature) {
        FlagContext ctx = contextResolver.currentContext();
        if (!platformFlagService.isEnabledForTenant(feature.key(), ctx.targetId(), ctx.segment())) {
            return false;
        }
        return ctx.targetId() != null && entitlementResolver.includes(ctx.targetId(), feature);
    }

    /**
     * Enforce access, distinguishing <i>why</i> it is denied (see {@link FeatureAccessException}):
     * <ul>
     *   <li>platform flag off (kill switch / rollout / unregistered) → {@link FeatureDisabledException}
     *       (HTTP 404) — unavailable regardless of plan;</li>
     *   <li>flag on but the plan lacks the feature (or no tenant is bound) →
     *       {@link FeatureNotAvailableException} (HTTP 402) — upgrade is the remedy.</li>
     * </ul>
     */
    public void require(SubscriptionFeature feature) {
        FlagContext ctx = contextResolver.currentContext();
        if (!platformFlagService.isEnabledForTenant(feature.key(), ctx.targetId(), ctx.segment())) {
            throw new FeatureDisabledException(feature);
        }
        if (ctx.targetId() == null || !entitlementResolver.includes(ctx.targetId(), feature)) {
            throw new FeatureNotAvailableException(feature);
        }
    }
}
