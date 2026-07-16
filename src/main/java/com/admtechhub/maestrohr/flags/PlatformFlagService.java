package com.admtechhub.maestrohr.flags;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.flags.FeatureFlagOverride.TargetType;
import com.admtechhub.maestrohr.subscription.FeatureFlagOverrideRepository;
import com.admtechhub.maestrohr.subscription.PlatformFlagRepository;
import io.github.adeyinka7789.wunmi.FlagEngine;
import io.github.adeyinka7789.wunmi.FlagKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * MaestroHR's flag facade. <b>Resolution</b> is delegated to the wunmi {@link FlagEngine} (a single
 * shared engine — see {@link FlagEngineConfig}); <b>management</b> (the SUPER_ADMIN admin operations)
 * is done here directly against the {@code platform_flags} / {@code feature_flag_overrides}
 * repositories, returning MaestroHR entities that the admin UI renders, and writing the audit trail.
 *
 * <p>The wunmi engine reads through {@link com.admtechhub.maestrohr.subscription.JpaFlagStore}, so
 * management writes here are immediately visible to resolution (same tables).
 *
 * <p><b>Fail-safe-disabled:</b> a flag with no {@code platform_flags} row resolves to disabled; the
 * seed migration and {@link PlatformFlagSeeder} register every {@code SubscriptionFeature}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformFlagService {

    private final FlagEngine flagEngine;
    private final PlatformFlagRepository flagRepository;
    private final FeatureFlagOverrideRepository overrideRepository;
    private final AuditTrailService auditTrailService;

    // ── Resolution (delegated to the wunmi engine) ──────────────────────────────

    /** Global platform-switch check (no tenant/plan/rollout), by {@link FlagKey}. */
    public boolean isEnabled(FlagKey key) {
        return flagEngine.isEnabled(key.key());
    }

    /** Global platform-switch check (no tenant/plan/rollout), by name. */
    public boolean isEnabled(String flagName) {
        return flagEngine.isEnabled(flagName);
    }

    /**
     * Full layered resolution for an explicit tenant + plan: global kill switch → tenant override
     * → plan override → rollout. {@code tenantId}/{@code planName} may be null (those layers are
     * then skipped). MaestroHR's tenant maps to the engine's subject, its plan to the segment.
     */
    public boolean isEnabledForTenant(String flagName, UUID tenantId, String planName) {
        return flagEngine.resolve(flagName, tenantId != null ? tenantId.toString() : null, planName);
    }

    // ── Management (direct repository access + audit) ────────────────────────────

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
        audit(flagName, updatedBy, "Flag " + flagName + " changed: rollout=" + percentage + "%");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<FeatureFlagOverride> listOverrides(String flagName) {
        return overrideRepository.findByFlagName(flagName);
    }

    /**
     * Create or update the override for the given flag/target. Ensures a {@code platform_flags}
     * row exists first (resolution bails at the missing-flag gate before consulting overrides, so
     * an override on a name with no flag row would be a silent no-op).
     */
    @Transactional
    public FeatureFlagOverride createOverride(String flagName, TargetType targetType, String targetValue,
                                               boolean enabled, String reason, String createdBy) {
        ensureFlagExists(flagName);
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
        audit(flagName, createdBy, "Flag " + flagName + " changed: override " + targetType + "="
                + targetValue + " enabled=" + enabled);
        return saved;
    }

    @Transactional
    public void deleteOverride(UUID overrideId) {
        FeatureFlagOverride override = overrideRepository.findById(overrideId).orElse(null);
        overrideRepository.deleteById(overrideId);
        if (override != null) {
            audit(override.getFlagName(), currentUserEmail(),
                    "Flag " + override.getFlagName() + " changed: override removed "
                            + override.getTargetType() + "=" + override.getTargetValue());
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private PlatformFlag setEnabled(String flagName, boolean enabled, String updatedBy) {
        PlatformFlag flag = flagRepository.findByName(flagName)
                .orElseGet(() -> PlatformFlag.builder().name(flagName).build());
        flag.setEnabled(enabled);
        flag.setUpdatedBy(updatedBy);
        PlatformFlag saved = flagRepository.save(flag);
        log.info("Platform flag '{}' set to enabled={} by {}", flagName, enabled, updatedBy);
        audit(flagName, updatedBy, "Flag " + flagName + " changed: enabled=" + enabled);
        return saved;
    }

    private void ensureFlagExists(String flagName) {
        if (flagRepository.findByName(flagName).isEmpty()) {
            flagRepository.save(PlatformFlag.builder().name(flagName).enabled(true).build());
            log.info("Auto-created platform flag '{}' (enabled) to back a new override", flagName);
        }
    }

    private void audit(String flagName, String actor, String detail) {
        auditTrailService.record(null, actor, "FEATURE_FLAG_CHANGED", "PLATFORM_FLAG",
                flagName, "/admin/feature-flags", "POST", null, 200, detail);
    }

    private String currentUserEmail() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }
}
