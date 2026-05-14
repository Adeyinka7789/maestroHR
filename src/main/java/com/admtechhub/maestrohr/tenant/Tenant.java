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

    // New fields for payment
    @Column(name = "paystack_subscription_code")
    private String paystackSubscriptionCode;

    @Column(name = "paystack_customer_code")
    private String paystackCustomerCode;

    @Column(name = "payment_period")
    @Enumerated(EnumType.STRING)
    private PaymentPeriod paymentPeriod;

    @Column(name = "auto_renew", nullable = false)
    @Builder.Default
    private boolean autoRenew = true;

    @Column(name = "disbursement_provider", nullable = false, length = 50)
    @Builder.Default
    private String disbursementProvider = "CSV";
}