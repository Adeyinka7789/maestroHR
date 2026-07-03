package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.subscription.FeatureFlagOverride.TargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages global {@link PlatformFlag} kill switches, plus per-tenant/per-plan
 * {@link FeatureFlagOverride}s and rollout-percentage bucketing layered on top. Platform-wide
 * (not tenant-scoped): a SUPER_ADMIN toggles a flag/override and it takes effect immediately.
 *
 * <p><b>Fail-safe-disabled semantics:</b> a flag with no {@code platform_flags} row is treated
 * as disabled — see {@link #isEnabledForTenant}. In practice {@link PlatformFlagSeeder} seeds
 * every {@code SubscriptionFeature} at boot, so this only bites a genuinely unregistered flag
 * name (typo, stale reference, or a pre-seed boot race), which should fail closed rather than
 * silently open. {@link #enable}/{@link #disable} create the row on first toggle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformFlagService {

    private static final String FLAG_CACHE_ATTR = "PlatformFlagService.flagCache";
    private static final String OVERRIDE_CACHE_ATTR = "PlatformFlagService.overrideCache";

    private final PlatformFlagRepository flagRepository;
    private final FeatureFlagOverrideRepository overrideRepository;
    private final AuditTrailService auditTrailService;

    /**
     * Whether the named flag is on, with no tenant/plan context. Backward-compatible entry
     * point: delegates to {@link #isEnabledForTenant} with a null tenant and plan, which skips
     * the override and rollout layers. Absent flag → {@code false} (fail-safe-disabled).
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagName) {
        return isEnabledForTenant(flagName, null, null);
    }

    /**
     * Layered resolution, short-circuiting on the first layer that applies:
     * <ol>
     *   <li>No {@code platform_flags} row for this name → log a warning and return
     *       {@code false} (fail-safe-disabled; see class Javadoc)</li>
     *   <li>Global kill switch off → {@code false}, <b>absolute</b> — no override of any kind
     *       can revive a globally-killed flag, so neither override lookup even runs</li>
     *   <li>Tenant override exists → that override's value</li>
     *   <li>Plan override exists → that override's value</li>
     *   <li>Rollout percentage &lt; 100 (requires a tenant) → consistent-hash bucket test</li>
     *   <li>Global default → {@code true} (the flag row exists and is enabled, by this point)</li>
     * </ol>
     * {@code tenantId}/{@code planName} may be null, in which case the corresponding override
     * layers (and rollout, which requires a tenant) are skipped.
     */
    @Transactional(readOnly = true)
    public boolean isEnabledForTenant(String flagName, UUID tenantId, String planName) {
        Map<String, PlatformFlag> cache = requestScopedFlagCache();
        PlatformFlag flag = cache != null ? cache.get(flagName) : flagRepository.findByName(flagName).orElse(null);

        if (flag == null) {
            log.warn("Feature flag '{}' has no platform_flags row; defaulting to disabled", flagName);
            return false;
        }

        if (!flag.isEnabled()) {
            return false;
        }

        if (tenantId != null) {
            Optional<FeatureFlagOverride> tenantOverride =
                    cachedOverride(flagName, TargetType.TENANT, tenantId.toString());
            if (tenantOverride.isPresent()) {
                return tenantOverride.get().isEnabled();
            }
        }

        if (planName != null) {
            Optional<FeatureFlagOverride> planOverride = cachedOverride(flagName, TargetType.PLAN, planName);
            if (planOverride.isPresent()) {
                return planOverride.get().isEnabled();
            }
        }

        int rolloutPercentage = flag.getRolloutPercentage();
        if (tenantId != null && rolloutPercentage < 100) {
            int bucket = Math.floorMod((tenantId.toString() + flagName).hashCode(), 100);
            return bucket < rolloutPercentage;
        }

        return true;
    }

    /**
     * Loads every {@link PlatformFlag} once per HTTP request and caches it in request
     * attributes, so a request that calls {@link #isEnabledForTenant} many times (e.g. rendering
     * a page with several gated widgets) hits the DB once instead of once per call. Returns
     * {@code null} when there is no request context (background jobs, tests), in which case
     * callers fall back to a direct per-flag query.
     */
    @SuppressWarnings("unchecked")
    private Map<String, PlatformFlag> requestScopedFlagCache() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        Map<String, PlatformFlag> cache = (Map<String, PlatformFlag>)
                attrs.getAttribute(FLAG_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (cache == null) {
            cache = flagRepository.findAll().stream()
                    .collect(Collectors.toMap(PlatformFlag::getName, f -> f));
            attrs.setAttribute(FLAG_CACHE_ATTR, cache, RequestAttributes.SCOPE_REQUEST);
        }
        return cache;
    }

    /**
     * Request-scoped cache for individual override lookups, keyed by (flagName, targetType,
     * targetValue) — a request checking several flags for the same tenant/plan combination
     * doesn't re-query an override it has already fetched. Unlike {@link #requestScopedFlagCache},
     * this is filled lazily per key rather than preloaded, since eagerly loading every override
     * row would defeat the point of scoping the cache to just what a request actually looks up.
     * Falls back to a direct query when there is no request context, same as the flag cache.
     */
    @SuppressWarnings("unchecked")
    private Optional<FeatureFlagOverride> cachedOverride(String flagName, TargetType targetType, String targetValue) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(flagName, targetType, targetValue);
        }
        Map<String, Optional<FeatureFlagOverride>> cache = (Map<String, Optional<FeatureFlagOverride>>)
                attrs.getAttribute(OVERRIDE_CACHE_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (cache == null) {
            cache = new HashMap<>();
            attrs.setAttribute(OVERRIDE_CACHE_ATTR, cache, RequestAttributes.SCOPE_REQUEST);
        }
        String key = flagName + '|' + targetType + '|' + targetValue;
        return cache.computeIfAbsent(key, k ->
                overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(flagName, targetType, targetValue));
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
        auditTrailService.record(null, updatedBy, "FEATURE_FLAG_CHANGED",
                "PLATFORM_FLAG", flagName, "/admin/feature-flags", "POST",
                null, 200, "Flag " + flagName + " changed: rollout=" + percentage + "%");
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
        auditTrailService.record(null, createdBy, "FEATURE_FLAG_CHANGED",
                "PLATFORM_FLAG", flagName, "/admin/feature-flags", "POST",
                null, 200, "Flag " + flagName + " changed: override " + targetType + "=" + targetValue
                        + " enabled=" + enabled);
        return saved;
    }

    @Transactional
    public void deleteOverride(UUID overrideId) {
        FeatureFlagOverride override = overrideRepository.findById(overrideId).orElse(null);
        overrideRepository.deleteById(overrideId);
        if (override != null) {
            String actor = currentUserEmail();
            auditTrailService.record(null, actor, "FEATURE_FLAG_CHANGED",
                    "PLATFORM_FLAG", override.getFlagName(), "/admin/feature-flags", "POST",
                    null, 200, "Flag " + override.getFlagName() + " changed: override removed "
                            + override.getTargetType() + "=" + override.getTargetValue());
        }
    }

    private PlatformFlag setEnabled(String flagName, boolean enabled, String updatedBy) {
        PlatformFlag flag = flagRepository.findByName(flagName)
                .orElseGet(() -> PlatformFlag.builder().name(flagName).build());
        flag.setEnabled(enabled);
        flag.setUpdatedBy(updatedBy);
        PlatformFlag saved = flagRepository.save(flag);
        log.info("Platform flag '{}' set to enabled={} by {}", flagName, enabled, updatedBy);
        auditTrailService.record(null, updatedBy, "FEATURE_FLAG_CHANGED",
                "PLATFORM_FLAG", flagName, "/admin/feature-flags", "POST",
                null, 200, "Flag " + flagName + " changed: enabled=" + enabled);
        return saved;
    }

    private String currentUserEmail() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }
}
