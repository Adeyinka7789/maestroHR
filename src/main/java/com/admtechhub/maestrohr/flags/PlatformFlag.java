package com.admtechhub.maestrohr.flags;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A global, platform-wide feature flag (a Waffle-style kill switch). PLATFORM-GLOBAL — no
 * tenant_id and no RLS (modeled on broadcasts, V28) — toggled by a SUPER_ADMIN to enable or
 * disable a feature across every tenant at once.
 *
 * <p>Effective availability of a {@link com.admtechhub.maestrohr.tenant.SubscriptionFeature}
 * is: this global flag enabled AND the tenant's plan includes the feature (see
 * {@link FeatureAccessService}). A feature with no flag row is treated as disabled (fail-closed);
 * the seed migration and {@link PlatformFlagSeeder} register every known flag.
 */
@Entity
@Table(name = "platform_flags")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class PlatformFlag extends BaseEntity {

    /** Flag key — matches the {@code SubscriptionFeature} enum name for feature gates. */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "description", length = 500)
    private String description;

    /** Email of the SUPER_ADMIN who last toggled the flag. */
    @Column(name = "updated_by")
    private String updatedBy;

    /** Percentage of tenants (by consistent hash) that get this flag when no override applies. */
    @Column(name = "rollout_percentage", nullable = false)
    @Builder.Default
    private int rolloutPercentage = 100;
}
