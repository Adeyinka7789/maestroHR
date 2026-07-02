package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.subscription.FeatureFlagOverride.TargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages global {@link PlatformFlag} kill switches, plus per-tenant/per-plan
 * {@link FeatureFlagOverride}s and rollout-percentage bucketing layered on top. Platform-wide
 * (not tenant-scoped): a SUPER_ADMIN toggles a flag/override and it takes effect immediately.
 *
 * <p><b>Default-enabled semantics:</b> a flag with no row is treated as enabled. This keeps
 * every existing feature working unchanged — only features explicitly seeded/flagged (today
 * just {@code LOAN_MANAGEMENT}) are subject to a global switch. {@link #enable}/{@link #disable}
 * create the row on first toggle so a never-seeded feature can still be switched off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformFlagService {

    private final PlatformFlagRepository flagRepository;
    private final FeatureFlagOverrideRepository overrideRepository;

    /**
     * Whether the named flag is on, with no tenant/plan context. Backward-compatible entry
     * point: delegates to {@link #isEnabledForTenant} with a null tenant and plan, which skips
     * the override and rollout layers and falls straight through to the global default.
     * Absent flag → {@code true} (unchanged historical behavior).
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagName) {
        return isEnabledForTenant(flagName, null, null);
    }

    /**
     * Layered resolution, short-circuiting on the first layer that applies. Tenant/plan
     * overrides are targeting rules and take precedence over the global kill switch (e.g. an
     * early-access tenant can be force-enabled while a feature is globally killed for an
     * incident):
     * <ol>
     *   <li>Tenant override exists → that override's value</li>
     *   <li>Plan override exists → that override's value</li>
     *   <li>Global kill switch off → {@code false}</li>
     *   <li>Rollout percentage &lt; 100 (requires a tenant) → consistent-hash bucket test</li>
     *   <li>Global default (flag's {@code enabled}, or {@code true} if no row)</li>
     * </ol>
     * {@code tenantId}/{@code planName} may be null, in which case the corresponding override
     * layers (and rollout, which requires a tenant) are skipped.
     */
    @Transactional(readOnly = true)
    public boolean isEnabledForTenant(String flagName, UUID tenantId, String planName) {
        if (tenantId != null) {
            Optional<FeatureFlagOverride> tenantOverride = overrideRepository
                    .findByFlagNameAndTargetTypeAndTargetValue(flagName, TargetType.TENANT, tenantId.toString());
            if (tenantOverride.isPresent()) {
                return tenantOverride.get().isEnabled();
            }
        }

        if (planName != null) {
            Optional<FeatureFlagOverride> planOverride = overrideRepository
                    .findByFlagNameAndTargetTypeAndTargetValue(flagName, TargetType.PLAN, planName);
            if (planOverride.isPresent()) {
                return planOverride.get().isEnabled();
            }
        }

        PlatformFlag flag = flagRepository.findByName(flagName).orElse(null);

        if (flag != null && !flag.isEnabled()) {
            return false;
        }

        int rolloutPercentage = flag != null ? flag.getRolloutPercentage() : 100;
        if (tenantId != null && rolloutPercentage < 100) {
            int bucket = Math.floorMod((tenantId.toString() + flagName).hashCode(), 100);
            return bucket < rolloutPercentage;
        }

        return flag == null || flag.isEnabled();
    }

    /** Turn the flag on, creating it if it does not yet exist. */
    @Transactional
    public PlatformFlag enable(String flagName, String updatedBy) {
        return setEnabled(flagName, true, updatedBy);
    }

    /** Turn the flag off, creating it if it does not yet exist. */
    @Transactional
    public PlatformFlag disable(String flagName, String updatedBy) {
        return setEnabled(flagName, false, updatedBy);
    }

    @Transactional(readOnly = true)
    public List<PlatformFlag> listAll() {
        return flagRepository.findAllByOrderByNameAsc();
    }

    /** Set the rollout percentage for a flag, creating the row if it does not yet exist. */
    @Transactional
    public PlatformFlag setRolloutPercentage(String flagName, int percentage, String updatedBy) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Rollout percentage must be between 0 and 100");
        }
        PlatformFlag flag = flagRepository.findByName(flagName)
                .orElseGet(() -> PlatformFlag.builder().name(flagName).build());
        flag.setRolloutPercentage(percentage);
        flag.setUpdatedBy(updatedBy);
        PlatformFlag saved = flagRepository.save(flag);
        log.info("Platform flag '{}' rollout set to {}% by {}", flagName, percentage, updatedBy);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<FeatureFlagOverride> listOverrides(String flagName) {
        return overrideRepository.findByFlagName(flagName);
    }

    /** Create or update the override for the given flag/target, keyed by the unique (flag, type, value) tuple. */
    @Transactional
    public FeatureFlagOverride createOverride(String flagName, TargetType targetType, String targetValue,
                                               boolean enabled, String reason, String createdBy) {
        FeatureFlagOverride override = overrideRepository
                .findByFlagNameAndTargetTypeAndTargetValue(flagName, targetType, targetValue)
                .orElseGet(() -> FeatureFlagOverride.builder()
                        .flagName(flagName)
                        .targetType(targetType)
                        .targetValue(targetValue)
                        .build());
        override.setEnabled(enabled);
        override.setReason(reason);
        override.setCreatedBy(createdBy);
        FeatureFlagOverride saved = overrideRepository.save(override);
        log.info("Feature flag override '{}' [{} {}] set to enabled={} by {}",
                flagName, targetType, targetValue, enabled, createdBy);
        return saved;
    }

    @Transactional
    public void deleteOverride(UUID overrideId) {
        overrideRepository.deleteById(overrideId);
    }

    private PlatformFlag setEnabled(String flagName, boolean enabled, String updatedBy) {
        PlatformFlag flag = flagRepository.findByName(flagName)
                .orElseGet(() -> PlatformFlag.builder().name(flagName).build());
        flag.setEnabled(enabled);
        flag.setUpdatedBy(updatedBy);
        PlatformFlag saved = flagRepository.save(flag);
        log.info("Platform flag '{}' set to enabled={} by {}", flagName, enabled, updatedBy);
        return saved;
    }
}
