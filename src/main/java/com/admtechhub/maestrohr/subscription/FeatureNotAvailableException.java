package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.Getter;

/**
 * Thrown when the current tenant's subscription plan does not include a feature gated by
 * {@link RequiresFeature}. Mapped to HTTP 402 (Payment Required) in
 * {@code GlobalExceptionHandler}.
 */
@Getter
public class FeatureNotAvailableException extends RuntimeException {

    private final SubscriptionFeature feature;

    public FeatureNotAvailableException(SubscriptionFeature feature) {
        super("Upgrade your plan to access " + feature);
        this.feature = feature;
    }
}
