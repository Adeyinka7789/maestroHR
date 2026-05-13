package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant extends BaseEntity {

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "rc_number", unique = true, length = 50)
    private String rcNumber;

    @Column(name = "industry", nullable = false, length = 100)
    private String industry;

    @Column(name = "company_size", nullable = false, length = 50)
    private String companySize;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_plan", nullable = false, length = 50)
    @Builder.Default
    private SubscriptionPlan subscriptionPlan = SubscriptionPlan.FREE_TRIAL;

    @Column(name = "subscription_expires_at", nullable = false)
    private OffsetDateTime subscriptionExpiresAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}