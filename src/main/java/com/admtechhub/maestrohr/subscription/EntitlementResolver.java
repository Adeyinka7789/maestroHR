package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;

import java.util.UUID;

/**
 * Whether a tenant's <b>plan entitles</b> it to a feature — the billing half of feature access,
 * kept deliberately separate from the flag engine. A flag says "is this feature switched on?";
 * entitlement says "has this tenant paid for it?". The two are composed by
 * {@link FeatureAccessService}.
 *
 * <p>This is application-specific and is <b>not</b> part of the extractable flag library: a
 * consumer without a subscription model simply doesn't have (or need) it.
 */
public interface EntitlementResolver {

    /** True if {@code tenantId}'s current plan includes {@code feature}. */
    boolean includes(UUID tenantId, SubscriptionFeature feature);
}
