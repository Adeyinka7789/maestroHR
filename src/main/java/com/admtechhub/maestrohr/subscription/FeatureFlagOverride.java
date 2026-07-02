package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A per-tenant or per-plan override for a {@link PlatformFlag}. PLATFORM-GLOBAL — no tenant_id
 * and no RLS (same rationale as {@link PlatformFlag}, V30/V46) — an override row lets a
 * SUPER_ADMIN force a flag on/off for one tenant or one {@code SubscriptionPlan} regardless of
 * the global switch, taking precedence in {@link PlatformFlagService#isEnabledForTenant}.
 */
@Entity
@Table(name = "feature_flag_overrides")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class FeatureFlagOverride extends BaseEntity {

    /** Matches {@code platform_flags.name} / the {@code SubscriptionFeature} enum name. */
    @Column(name = "flag_name", nullable = false, length = 100)
    private String flagName;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    /** Tenant UUID (as string) when {@code targetType == TENANT}, or plan name when {@code PLAN}. */
    @Column(name = "target_value", nullable = false, length = 100)
    private String targetValue;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "reason", length = 500)
    private String reason;

    /** Email of the SUPER_ADMIN who created/last updated this override. */
    @Column(name = "created_by")
    private String createdBy;

    public enum TargetType {
        TENANT,
        PLAN
    }
}
