package com.admtechhub.maestrohr.subscription;

/**
 * Lifecycle state of a tenant's subscription. Drives feature access in
 * {@link SubscriptionService#hasFeature}.
 */
public enum SubscriptionStatus {
    /** On a free trial — features available until the trial period ends. */
    TRIALING,
    /** Paid and current — full feature access for the plan. */
    ACTIVE,
    /** Payment failed/overdue but not yet cancelled — treated as no access. */
    PAST_DUE,
    /** Explicitly cancelled by the tenant or an admin — no access. */
    CANCELLED,
    /** Period elapsed without renewal — no access. */
    EXPIRED
}
