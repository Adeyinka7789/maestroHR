/**
 * The extractable feature-flag engine (working name <b>wunmi</b>). Everything in this package is
 * intended to be lifted, unchanged, into a standalone library; it has no dependency on MaestroHR
 * domain types (tenants, billing, audit, security).
 *
 * <h2>What's here (the core)</h2>
 * <ul>
 *   <li>{@link com.admtechhub.maestrohr.flags.PlatformFlagService} — the engine: layered
 *       resolution (global kill switch → tenant/plan override → rollout) + flag/override management.</li>
 *   <li>{@link com.admtechhub.maestrohr.flags.FlagKey} — typed flag handle an app enum implements.</li>
 *   <li>SPIs the host application implements:
 *       {@link com.admtechhub.maestrohr.flags.FlagStore} (persistence),
 *       {@link com.admtechhub.maestrohr.flags.FlagAuditListener} (audit),
 *       {@link com.admtechhub.maestrohr.flags.FlagCache} (caching),
 *       {@link com.admtechhub.maestrohr.flags.FlagContextResolver} (who is asking).</li>
 *   <li>{@link com.admtechhub.maestrohr.flags.DefaultFlagCache} — request-scoped + TTL cache impl.</li>
 *   <li>{@link com.admtechhub.maestrohr.flags.PlatformFlag} /
 *       {@link com.admtechhub.maestrohr.flags.FeatureFlagOverride} — the model.</li>
 * </ul>
 *
 * <h2>What stays in the application (not part of the library)</h2>
 * The MaestroHR adapters and composition live in {@code ..subscription}: {@code JpaFlagStore}
 * (Spring Data impl), {@code AuditTrailFlagListener}, {@code TenantFlagContextResolver},
 * {@code EntitlementResolver}/{@code PlanEntitlementResolver} (billing — deliberately outside the
 * flag engine), and {@code FeatureAccessService} (composes flag ∧ entitlement). The Spring gating
 * ({@code @RequiresFeature}, {@code FeatureCheckAspect}) and the 402/404 exceptions are app-side too.
 *
 * <h2>Remaining before the cut</h2>
 * {@link com.admtechhub.maestrohr.flags.PlatformFlag} / {@link com.admtechhub.maestrohr.flags.FeatureFlagOverride}
 * are still JPA-annotated {@code @Entity} classes ({@code jakarta.persistence}); they stay here so
 * the model travels with the engine, but they carry Lombok getters the admin view/JSON depend on.
 * Swapping them for framework-free records (and reworking the admin view's property access) is the
 * final pre-extraction step.
 */
package com.admtechhub.maestrohr.flags;
