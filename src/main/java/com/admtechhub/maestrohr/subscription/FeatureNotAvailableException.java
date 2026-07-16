package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;

/**
 * Thrown when the current tenant's subscription <b>plan does not include</b> a feature gated by
 * {@link RequiresFeature}. An entitlement miss — mapped to HTTP 402 (Payment Required) in
 * {@code GlobalExceptionHandler}, with an "upgrade your plan" message, because upgrading is the
 * remediation.
 *
 * <p>Contrast {@link FeatureDisabledException}, which is raised when the platform flag itself is
 * off (kill switch / rollout) — a case where upgrading would not help. See
 * {@link FeatureAccessException}.
 */
public class FeatureNotAvailableException extends FeatureAccessException {

    public FeatureNotAvailableException(SubscriptionFeature feature) {
        super("Upgrade your plan to access " + feature, feature);
    }
}
