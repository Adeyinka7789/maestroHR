package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
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

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "logo_file_name")
    private String logoFileName;

    /**
     * Public careers-page handle (V60). Forms the URL {@code /careers/{careers_slug}} at which
     * this tenant's PUBLISHED job postings are listed to unauthenticated candidates. Generated
     * at registration (see {@code platform.TenantUserWrites}) and backfilled for existing
     * tenants; unique. No {@code @SQLRestriction} on Tenant — resolution from the public,
     * session-less path goes through the privileged datasource ({@code CareersPublicRepository}).
     */
    @Column(name = "careers_slug", length = 80)
    private String careersSlug;

    /** Owner switch for the public careers page. When false the page returns "not available". */
    @Column(name = "careers_enabled", nullable = false)
    @Builder.Default
    private boolean careersEnabled = true;

    /** Optional tagline rendered on the careers landing page (e.g. "Join our mission..."). */
    @Column(name = "careers_intro", length = 500)
    private String careersIntro;

    /**
     * Soft-delete marker for self-service company deletion (V55). Non-null means the owner has
     * trashed this company: it is deactivated and hidden from login/switcher, kept for a 90-day
     * grace period on the super-admin trash page, then permanently purged by
     * {@code SoftDeleteCleanupJob}. No {@code @SQLRestriction} here — access is already gated by
     * {@code is_active} (shared with the suspend feature), and the trash/purge paths read this
     * column directly through the privileged datasource.
     */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}