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
            30,
            "Free 30-day trial",
            SubscriptionFeature.BASIC_EMPLOYEES,
            SubscriptionFeature.BASIC_PAYROLL,
            SubscriptionFeature.EMAIL_SUPPORT
    ),
    BASIC(
            "Basic",
            25000,
            0,
            "Perfect for small businesses",
            SubscriptionFeature.BASIC_EMPLOYEES,
            SubscriptionFeature.BASIC_PAYROLL,
            SubscriptionFeature.EMAIL_SUPPORT,
            SubscriptionFeature.SMS_NOTIFICATIONS
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
            SubscriptionFeature.LEAVE_MANAGEMENT
    ),
    ENTERPRISE(
            "Enterprise",
            200000,
            0,
            "Custom solutions for large organizations",
            SubscriptionFeature.UNLIMITED_EMPLOYEES,
            SubscriptionFeature.ADVANCED_PAYROLL,
            SubscriptionFeature.API_ACCESS,
            SubscriptionFeature.PRIORITY_SUPPORT,
            SubscriptionFeature.CUSTOM_REPORTING,
            SubscriptionFeature.SMS_NOTIFICATIONS,
            SubscriptionFeature.LEAVE_MANAGEMENT,
            SubscriptionFeature.ATTENDANCE_TRACKING
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

    public long getAnnualPriceKobo() {
        return priceKobo * 12;
    }

    public long getQuarterlyPriceKobo() {
        return priceKobo * 3;
    }
}