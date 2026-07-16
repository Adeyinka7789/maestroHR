package com.admtechhub.maestrohr.flags;

import java.util.UUID;

/**
 * Resolves the current targeting context (tenant + plan) for a feature-access check. Used by
 * {@code FeatureAccessService} to obtain the tenant and plan once and pass them to both the flag
 * gate ({@code PlatformFlagService.isEnabledForTenant}) and the entitlement gate.
 *
 * <p>The MaestroHR implementation, {@code TenantFlagContextResolver}, reads the current tenant from
 * {@code TenantContext} and its plan.
 */
public interface FlagContextResolver {

    /** The current context, or {@link FlagContext#EMPTY} when nothing is bound (jobs, unauthenticated). */
    FlagContext currentContext();

    /**
     * The two targeting dimensions the engine resolves against.
     *
     * @param targetId the primary subject a flag targets — drives {@code TENANT} overrides and
     *                 rollout bucketing; {@code null} when unbound (both layers are then skipped)
     * @param segment  a named group the subject belongs to — drives {@code PLAN} overrides;
     *                 {@code null} when unknown (the plan-override layer is then skipped)
     */
    record FlagContext(UUID targetId, String segment) {
        public static final FlagContext EMPTY = new FlagContext(null, null);
    }
}
