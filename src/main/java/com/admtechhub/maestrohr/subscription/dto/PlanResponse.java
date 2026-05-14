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

    public static PlanResponse from(SubscriptionPlan plan) {
        return PlanResponse.builder()
                .name(plan.name())
                .displayName(plan.getDisplayName())
                .monthlyPrice(plan.getPriceKobo() / 100)
                .quarterlyPrice(plan.getQuarterlyPriceKobo() / 100)
                .annualPrice(plan.getAnnualPriceKobo() / 100)
                .monthlyPriceKobo(plan.getPriceKobo())
                .features(plan.getFeatures())
                .description(plan.getDescription())
                .build();
    }
}