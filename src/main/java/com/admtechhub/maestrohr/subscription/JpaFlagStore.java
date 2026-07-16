package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.subscription.FeatureFlagOverride.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link FlagStore}: Spring Data JPA over {@code platform_flags} and
 * {@code feature_flag_overrides}. A thin adapter — it holds no logic, only maps the SPI onto the
 * two repositories — so the engine ({@link PlatformFlagService}) has no direct Spring Data
 * dependency. Swap this bean to back the engine with anything else.
 */
@Component
@RequiredArgsConstructor
public class JpaFlagStore implements FlagStore {

    private final PlatformFlagRepository flagRepository;
    private final FeatureFlagOverrideRepository overrideRepository;

    @Override
    public Optional<PlatformFlag> findFlag(String name) {
        return flagRepository.findByName(name);
    }

    @Override
    public List<PlatformFlag> findAllFlags() {
        return flagRepository.findAll();
    }

    @Override
    public List<PlatformFlag> findAllFlagsOrderedByName() {
        return flagRepository.findAllByOrderByNameAsc();
    }

    @Override
    public PlatformFlag saveFlag(PlatformFlag flag) {
        return flagRepository.save(flag);
    }

    @Override
    public Optional<FeatureFlagOverride> findOverride(String flagName, TargetType targetType, String targetValue) {
        return overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(flagName, targetType, targetValue);
    }

    @Override
    public List<FeatureFlagOverride> findOverridesByFlag(String flagName) {
        return overrideRepository.findByFlagName(flagName);
    }

    @Override
    public FeatureFlagOverride saveOverride(FeatureFlagOverride override) {
        return overrideRepository.save(override);
    }

    @Override
    public Optional<FeatureFlagOverride> findOverrideById(UUID id) {
        return overrideRepository.findById(id);
    }

    @Override
    public void deleteOverrideById(UUID id) {
        overrideRepository.deleteById(id);
    }
}
