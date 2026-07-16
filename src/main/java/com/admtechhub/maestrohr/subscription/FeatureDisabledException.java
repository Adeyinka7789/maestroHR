package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;

/**
 * Thrown when a feature gated by {@link RequiresFeature} is <b>switched off at the platform
 * level</b> — a global kill switch, a rollout exclusion, or an unregistered flag (see
 * {@link PlatformFlagService#isEnabledForTenant}). Distinct from an entitlement miss: the
 * feature is unavailable regardless of the tenant's plan, so upgrading would not help.
 *
 * <p>Mapped to HTTP 404 (Not Found) in {@code GlobalExceptionHandler} — a killed feature should
 * not advertise itself as merely "unpaid". Contrast {@link FeatureNotAvailableException} (402).
 */
public class FeatureDisabledException extends FeatureAccessException {

    public FeatureDisabledException(SubscriptionFeature feature) {
        super(featureLabel(feature) + " is not available right now", feature);
    }

    private static String featureLabel(SubscriptionFeature feature) {
        return feature.name().charAt(0)
                + feature.name().substring(1).toLowerCase().replace('_', ' ');
    }
}
