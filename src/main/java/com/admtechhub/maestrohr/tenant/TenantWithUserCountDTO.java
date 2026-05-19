package com.admtechhub.maestrohr.tenant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantWithUserCountDTO {
    private UUID id;
    private String companyName;
    private String rcNumber;
    private String industry;
    private String companySize;
    private SubscriptionPlan subscriptionPlan;
    private OffsetDateTime subscriptionExpiresAt;
    private boolean isActive;
    private Long userCount;
}