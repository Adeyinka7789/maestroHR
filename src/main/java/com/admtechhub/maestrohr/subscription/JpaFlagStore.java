package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.flags.FeatureFlagOverride;
import com.admtechhub.maestrohr.flags.FeatureFlagOverride.TargetType;
import com.admtechhub.maestrohr.flags.PlatformFlag;
import io.github.adeyinka7789.wunmi.Flag;
import io.github.adeyinka7789.wunmi.FlagOverride;
import io.github.adeyinka7789.wunmi.FlagStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts MaestroHR's JPA persistence ({@code platform_flags} / {@code feature_flag_overrides})
 * to the wunmi {@link FlagStore} SPI: the wunmi {@link io.github.adeyinka7789.wunmi.FlagEngine}
 * resolves flags through this store while the rows stay as MaestroHR's
 * {@link PlatformFlag}/{@link FeatureFlagOverride} entities (which the admin UI still renders).
 *
 * <p>The engine's generic targeting scopes map onto MaestroHR's tenant/plan model:
 * wunmi {@code SUBJECT} ↔ {@code TargetType.TENANT}, wunmi {@code SEGMENT} ↔ {@code TargetType.PLAN}.
 */
@Component
@RequiredArgsConstructor
public class JpaFlagStore implements FlagStore {

    private final PlatformFlagRepository flagRepository;
    private final FeatureFlagOverrideRepository overrideRepository;

    // ── Flags ──────────────────────────────────────────────────────────────────

    @Override
    public Optional<Flag> findFlag(String name) {
        return flagRepository.findByName(name).map(JpaFlagStore::toFlag);
    }

    @Override
    public List<Flag> findAllFlags() {
        return flagRepository.findAll().stream().map(JpaFlagStore::toFlag).toList();
    }

    @Override
    public Flag saveFlag(Flag flag) {
        PlatformFlag entity = flagRepository.findByName(flag.name())
                .orElseGet(() -> PlatformFlag.builder().name(flag.name()).build());
        entity.setEnabled(flag.enabled());
        entity.setDescription(flag.description());
        entity.setRolloutPercentage(flag.rolloutPercentage());
        entity.setUpdatedBy(flag.updatedBy());
        return toFlag(flagRepository.save(entity));
    }

    // ── Overrides ──────────────────────────────────────────────────────────────

    @Override
    public Optional<FlagOverride> findOverride(String flagName, FlagOverride.Scope scope, String value) {
        return overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(flagName, toTargetType(scope), value)
                .map(JpaFlagStore::toOverride);
    }

    @Override
    public List<FlagOverride> findOverrides(String flagName) {
        return overrideRepository.findByFlagName(flagName).stream().map(JpaFlagStore::toOverride).toList();
    }

    @Override
    public FlagOverride saveOverride(FlagOverride override) {
        FeatureFlagOverride entity = (override.id() != null
                ? overrideRepository.findById(override.id()) : Optional.<FeatureFlagOverride>empty())
                .orElseGet(() -> FeatureFlagOverride.builder()
                        .flagName(override.flagName())
                        .targetType(toTargetType(override.scope()))
                        .targetValue(override.value())
                        .build());
        entity.setEnabled(override.enabled());
        entity.setReason(override.reason());
        entity.setCreatedBy(override.createdBy());
        return toOverride(overrideRepository.save(entity));
    }

    @Override
    public Optional<FlagOverride> findOverrideById(UUID id) {
        return overrideRepository.findById(id).map(JpaFlagStore::toOverride);
    }

    @Override
    public void deleteOverride(UUID id) {
        overrideRepository.deleteById(id);
    }

    // ── mapping ────────────────────────────────────────────────────────────────

    private static Flag toFlag(PlatformFlag e) {
        return new Flag(e.getName(), e.isEnabled(), e.getDescription(), e.getRolloutPercentage(), e.getUpdatedBy());
    }

    private static FlagOverride toOverride(FeatureFlagOverride e) {
        return new FlagOverride(e.getId(), e.getFlagName(), toScope(e.getTargetType()),
                e.getTargetValue(), e.isEnabled(), e.getReason(), e.getCreatedBy());
    }

    private static FlagOverride.Scope toScope(TargetType type) {
        return type == TargetType.PLAN ? FlagOverride.Scope.SEGMENT : FlagOverride.Scope.SUBJECT;
    }

    private static TargetType toTargetType(FlagOverride.Scope scope) {
        return scope == FlagOverride.Scope.SEGMENT ? TargetType.PLAN : TargetType.TENANT;
    }
}
