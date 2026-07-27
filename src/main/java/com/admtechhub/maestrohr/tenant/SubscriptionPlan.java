package com.admtechhub.maestrohr.tenant;

import lombok.Getter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Getter
public enum SubscriptionPlan {
    FREE_TRIAL(
            "Free Trial",
            0,
            14,
            "Free 14-day trial",
            SubscriptionFeature.BASIC_EMPLOYEES,
            SubscriptionFeature.BASIC_PAYROLL,
            SubscriptionFeature.EMAIL_SUPPORT,
            // Cross-cutting HR tools available on every plan today — entitled here so the admin
            // platform flag (not the plan) is the rollout/kill lever. Restrict per-plan later by
            // dropping the feature from a plan's set.
            SubscriptionFeature.EXIT_MANAGEMENT,
            SubscriptionFeature.COMPLIANCE,
            SubscriptionFeature.PUBLIC_HOLIDAYS
    ),
    BASIC(
            "Basic",
            25000,
            0,
            "Perfect for small businesses",
            SubscriptionFeature.BASIC_EMPLOYEES,
            SubscriptionFeature.BASIC_PAYROLL,
            SubscriptionFeature.EMAIL_SUPPORT,
            SubscriptionFeature.SMS_NOTIFICATIONS,
            SubscriptionFeature.ATTENDANCE_TRACKING,
            SubscriptionFeature.DOCUMENT_VAULT,
            SubscriptionFeature.EXIT_MANAGEMENT,
            SubscriptionFeature.COMPLIANCE,
            SubscriptionFeature.PUBLIC_HOLIDAYS
    ),
    PROFESSIONAL(
            "Professional",
            75000,
            0,
            "Ideal for growing companies",
            SubscriptionFeature.BASIC_EMPLOYEES,
            SubscriptionFeature.BASIC_PAYROLL,
            SubscriptionFeature.ADVANCED_PAYROLL,
            SubscriptionFeature.API_ACCESS,
            SubscriptionFeature.EMAIL_SUPPORT,
            SubscriptionFeature.SMS_NOTIFICATIONS,
            SubscriptionFeature.LEAVE_MANAGEMENT,
            SubscriptionFeature.ATTENDANCE_TRACKING,
            SubscriptionFeature.LOAN_MANAGEMENT,
            SubscriptionFeature.DOCUMENT_VAULT,
            SubscriptionFeature.RECRUITMENT,
            SubscriptionFeature.EXIT_MANAGEMENT,
            SubscriptionFeature.COMPLIANCE,
            SubscriptionFeature.PUBLIC_HOLIDAYS
    ),
    ENTERPRISE(
            "Enterprise",
            200000,
            0,
            "Custom solutions for large organizations",
            SubscriptionFeature.UNLIMITED_EMPLOYEES,
            SubscriptionFeature.BASIC_PAYROLL,
            SubscriptionFeature.ADVANCED_PAYROLL,
            SubscriptionFeature.API_ACCESS,
            SubscriptionFeature.PRIORITY_SUPPORT,
            SubscriptionFeature.CUSTOM_REPORTING,
            SubscriptionFeature.SMS_NOTIFICATIONS,
            SubscriptionFeature.LEAVE_MANAGEMENT,
            SubscriptionFeature.ATTENDANCE_TRACKING,
            SubscriptionFeature.HARDWARE_SYNC,
            SubscriptionFeature.LOAN_MANAGEMENT,
            SubscriptionFeature.DOCUMENT_VAULT,
            SubscriptionFeature.RECRUITMENT,
            SubscriptionFeature.EXIT_MANAGEMENT,
            SubscriptionFeature.COMPLIANCE,
            SubscriptionFeature.PUBLIC_HOLIDAYS
    );

    private final String displayName;
    private final long priceKobo;
    private final int trialDays;
    private final String description;
    private final Set<SubscriptionFeature> features;

    SubscriptionPlan(String displayName, long priceKobo, int trialDays,
                     String description, SubscriptionFeature... features) {
        this.displayName = displayName;
        this.priceKobo = priceKobo;
        this.trialDays = trialDays;
        this.description = description;
        this.features = new HashSet<>(Arrays.asList(features));
    }

    public boolean hasFeature(SubscriptionFeature feature) {
        return features.contains(feature);
    }

    /**
     * @deprecated Pricing's single source of truth is the {@code pricing_config} table,
     * read via {@code PricingService}. The enum's {@code priceKobo} now serves only as
     * the seed for default pricing; do not use it for billing or UI display.
     */
    @Deprecated
    public long getAnnualPriceKobo() {
        return priceKobo * 12;
    }

    /**
     * @deprecated Pricing's single source of truth is the {@code pricing_config} table,
     * read via {@code PricingService}. The enum's {@code priceKobo} now serves only as
     * the seed for default pricing; do not use it for billing or UI display.
     */
    @Deprecated
    public long getQuarterlyPriceKobo() {
        return priceKobo * 3;
    }
}