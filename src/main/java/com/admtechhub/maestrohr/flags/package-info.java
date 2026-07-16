/**
 * MaestroHR's feature-flag layer, now backed by the standalone <b>wunmi</b> engine
 * ({@code io.github.adeyinka7789.wunmi}).
 *
 * <h2>What's here</h2>
 * <ul>
 *   <li>{@link com.admtechhub.maestrohr.flags.PlatformFlag} /
 *       {@link com.admtechhub.maestrohr.flags.FeatureFlagOverride} — the JPA rows
 *       ({@code platform_flags} / {@code feature_flag_overrides}) the admin UI renders.</li>
 *   <li>{@link com.admtechhub.maestrohr.flags.FlagEngineConfig} — wires the wunmi
 *       {@code FlagEngine} used for <b>resolution</b>, backed by
 *       {@link com.admtechhub.maestrohr.subscription.JpaFlagStore} and
 *       {@link com.admtechhub.maestrohr.flags.DefaultFlagCache}.</li>
 *   <li>{@link com.admtechhub.maestrohr.flags.PlatformFlagService} — resolution delegates to the
 *       engine; flag <b>management</b> (admin CRUD + audit) is done here on the repositories.</li>
 *   <li>{@link com.admtechhub.maestrohr.flags.FlagContextResolver} — tenant/plan context for
 *       {@code FeatureAccessService} (which composes flag ∧ entitlement).</li>
 * </ul>
 *
 * <p>The generic engine scopes map onto MaestroHR's model in {@code JpaFlagStore}: wunmi
 * {@code SUBJECT} ↔ tenant, {@code SEGMENT} ↔ plan.
 */
package com.admtechhub.maestrohr.flags;
