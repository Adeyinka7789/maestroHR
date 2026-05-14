package com.admtechhub.maestrohr.tenant.dto;

import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class TenantSettingsResponse {
    private String companyName;
    private String industry;
    private String companySize;
    private SubscriptionPlan subscriptionPlan;
    private PaymentPeriod paymentPeriod;
    private OffsetDateTime subscriptionExpiresAt;
    private String disbursementProvider;
    private boolean autoRenew;
    private boolean isActive;
    private boolean paystackConnected;
}