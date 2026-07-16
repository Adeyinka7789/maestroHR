package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.Getter;

/**
 * Base type for the two distinct reasons a gated feature can be denied. They differ in cause
 * (and therefore in HTTP status and remediation) and must not be conflated:
 *
 * <ul>
 *   <li>{@link FeatureNotAvailableException} — the tenant's plan does not include the feature.
 *       An <b>entitlement</b> miss → HTTP 402, "upgrade your plan" (an action the tenant can take).</li>
 *   <li>{@link FeatureDisabledException} — the platform flag is off (kill switch, rollout
 *       exclusion, or an unregistered flag). A <b>flag</b> miss → HTTP 404: the feature simply
 *       isn't available and upgrading would not help, so it shouldn't advertise itself.</li>
 * </ul>
 *
 * <p>Web (HTMX) controllers catch this common supertype to render either denial as an in-place
 * banner; the JSON {@code GlobalExceptionHandler} maps each subtype to its own status code.
 */
@Getter
public abstract class FeatureAccessException extends RuntimeException {

    private final SubscriptionFeature feature;

    protected FeatureAccessException(String message, SubscriptionFeature feature) {
        super(message);
        this.feature = feature;
    }
}
