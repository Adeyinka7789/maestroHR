package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.subscription.FeatureFlagOverride.TargetType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence SPI for the flag engine — every read/write the engine performs against flag and
 * override storage goes through here. Extracting this behind an interface is what lets the
 * engine be reused with a different backing store (a different ORM, a cache, an in-memory map
 * for tests) without touching {@link PlatformFlagService}.
 *
 * <p>The default implementation is {@link JpaFlagStore} (Spring Data over {@code platform_flags}
 * / {@code feature_flag_overrides}). Part of the extractable core (future {@code wunmi} library).
 *
 * <p><b>Pre-extraction note:</b> the interface currently traffics in the JPA-annotated
 * {@link PlatformFlag} / {@link FeatureFlagOverride} entities. They behave as plain data holders
 * here, but a future step should replace them with framework-free records so the SPI carries no
 * persistence-framework types at all.
 */
public interface FlagStore {

    // ── Flags ──────────────────────────────────────────────────────────────────
    Optional<PlatformFlag> findFlag(String name);

    /** All flags, unordered — used to preload the request-scoped cache. */
    List<PlatformFlag> findAllFlags();

    /** All flags, ordered by name — used for admin listings. */
    List<PlatformFlag> findAllFlagsOrderedByName();

    PlatformFlag saveFlag(PlatformFlag flag);

    // ── Overrides ──────────────────────────────────────────────────────────────
    Optional<FeatureFlagOverride> findOverride(String flagName, TargetType targetType, String targetValue);

    List<FeatureFlagOverride> findOverridesByFlag(String flagName);

    FeatureFlagOverride saveOverride(FeatureFlagOverride override);

    Optional<FeatureFlagOverride> findOverrideById(UUID id);

    void deleteOverrideById(UUID id);
}
