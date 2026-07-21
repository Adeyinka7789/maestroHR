package com.admtechhub.maestrohr.tenant;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionPlanTest {

    /**
     * Guards the bug where HARDWARE_SYNC was granted by no plan at all — so even ENTERPRISE tenants
     * got "upgrade your plan". Every feature that exists must be entitled by at least one plan,
     * otherwise its gated page is unreachable for everyone.
     */
    @Test
    void everyFeatureIsGrantedByAtLeastOnePlan() {
        for (SubscriptionFeature feature : SubscriptionFeature.values()) {
            boolean grantedSomewhere = Arrays.stream(SubscriptionPlan.values())
                    .anyMatch(plan -> plan.hasFeature(feature));
            assertThat(grantedSomewhere)
                    .as("feature %s is granted by no plan — its gated page is unreachable for every tenant", feature)
                    .isTrue();
        }
    }

    /**
     * ENTERPRISE is the top tier, so it must grant everything PROFESSIONAL does — except the two
     * features PROFESSIONAL has that ENTERPRISE deliberately upgrades: BASIC_EMPLOYEES →
     * UNLIMITED_EMPLOYEES and EMAIL_SUPPORT → PRIORITY_SUPPORT. This catches an omitted feature
     * (BASIC_PAYROLL was missing).
     */
    @Test
    void enterpriseIsSupersetOfProfessional_exceptTierSubstitutions() {
        Set<SubscriptionFeature> substituted =
                Set.of(SubscriptionFeature.BASIC_EMPLOYEES, SubscriptionFeature.EMAIL_SUPPORT);

        for (SubscriptionFeature feature : SubscriptionPlan.PROFESSIONAL.getFeatures()) {
            if (substituted.contains(feature)) {
                continue;
            }
            assertThat(SubscriptionPlan.ENTERPRISE.hasFeature(feature))
                    .as("ENTERPRISE should grant PROFESSIONAL feature %s", feature)
                    .isTrue();
        }
    }

    @Test
    void enterpriseGrantsHardwareSyncAndBasicPayroll() {
        assertThat(SubscriptionPlan.ENTERPRISE.hasFeature(SubscriptionFeature.HARDWARE_SYNC)).isTrue();
        assertThat(SubscriptionPlan.ENTERPRISE.hasFeature(SubscriptionFeature.BASIC_PAYROLL)).isTrue();
    }
}
