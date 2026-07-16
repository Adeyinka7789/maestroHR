package com.admtechhub.maestrohr.subscription;

import java.util.UUID;

/**
 * Resolves the current targeting context for the flag engine — <i>who</i> a flag is being
 * evaluated for — so the engine can offer a no-argument {@link PlatformFlagService#isOn(FlagKey)}
 * without itself knowing where the caller's identity lives (a thread-local, a security context, a
 * request header, …). This is the seam that keeps the engine free of application identity plumbing.
 *
 * <p>The MaestroHR implementation, {@link TenantFlagContextResolver}, reads the current tenant
 * from {@code TenantContext} and its plan. Part of the extractable core (future {@code wunmi}).
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
