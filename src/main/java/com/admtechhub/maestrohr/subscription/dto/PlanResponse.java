package com.admtechhub.maestrohr.subscription.dto;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class PlanResponse {
    private String name;
    private String displayName;
    private long monthlyPrice;
    private long quarterlyPrice;
    private long annualPrice;
    private long monthlyPriceKobo;
    private Set<SubscriptionFeature> features;
    private String description;

    /**
     * Build a plan listing. Prices come from {@code pricing_config} (the single source
     * of truth) and are passed in as kobo; naira amounts are derived here. Feature set,
     * display name and description come from the {@link SubscriptionPlan} enum (config,
     * not pricing).
     */
    public static PlanResponse from(SubscriptionPlan plan,
                                    long monthlyKobo,
                                    long quarterlyKobo,
                                    long annualKobo) {
        return PlanResponse.builder()
                .name(plan.name())
                .displayName(plan.getDisplayName())
                .monthlyPrice(monthlyKobo / 100)
                .quarterlyPrice(quarterlyKobo / 100)
                .annualPrice(annualKobo / 100)
                .monthlyPriceKobo(monthlyKobo)
                .features(plan.getFeatures())
                .description(plan.getDescription())
                .build();
    }
}
