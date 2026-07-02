package com.admtechhub.maestrohr.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-global — no tenant scoping. The {@code maestro_app} role reads/writes
 * {@code feature_flag_overrides} freely (no RLS policy is attached, per V46).
 */
@Repository
public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverride, UUID> {

    Optional<FeatureFlagOverride> findByFlagNameAndTargetTypeAndTargetValue(
            String flagName, FeatureFlagOverride.TargetType targetType, String targetValue);

    List<FeatureFlagOverride> findByFlagName(String flagName);
}
