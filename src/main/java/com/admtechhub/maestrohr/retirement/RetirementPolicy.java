package com.admtechhub.maestrohr.retirement;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

/**
 * Single settings row per tenant governing retirement-date estimation (unlike
 * {@link com.admtechhub.maestrohr.loan.LoanPolicy} / {@link com.admtechhub.maestrohr.attendance.AttendancePolicy},
 * which support multiple named policies per tenant). Enforced by a unique constraint
 * on {@code tenant_id} in the migration.
 *
 * <p>Soft-deleted via {@code deleted_at}; the {@code @SQLRestriction} excludes deleted rows
 * from all JPA queries, matching the same pattern used for pay grades / loan policies.
 */
@Entity
@Table(name = "retirement_policies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class RetirementPolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @Column(name = "retirement_age", nullable = false)
    @Builder.Default
    private Integer retirementAge = 60;

    /** Comma-separated days-before-retirement thresholds for HR notifications, e.g. "180,30". */
    @Column(name = "notification_threshold_days", nullable = false, length = 200)
    @Builder.Default
    private String notificationThresholdDays = "180,30";

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
