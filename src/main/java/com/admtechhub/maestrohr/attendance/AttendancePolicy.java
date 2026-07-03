package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Late/absence deduction rules, scoped to a tenant. Mirrors {@link com.admtechhub.maestrohr.loan.LoanPolicy}'s
 * shape: a policy can be linked to specific {@link com.admtechhub.maestrohr.employee.PayGrade pay grades};
 * any employee whose grade has no linked policy falls back to a tenant default (resolution logic is Stage 2+).
 *
 * <p>{@code lateDeductionValue} / {@code absenceDeductionValue} are {@link BigDecimal} rather than a
 * {@code Long} kobo amount because the sibling {@code *DeductionType} can select either FLAT (a kobo sum)
 * or PERCENTAGE (e.g. 2.50) for the same column — a single decimal column covers both without a second
 * nullable field per deduction. LoanPolicy doesn't need this because it never mixes a flat/percentage
 * choice into one field (its multiplier/pct fields are always percentages).
 *
 * <p>Soft-deleted via {@code deleted_at}; the {@code @SQLRestriction} excludes deleted rows from all
 * JPA queries, matching the same pattern used for loan policies and pay grades.
 */
@Entity
@Table(name = "attendance_policies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class AttendancePolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** Minutes after shift start before a check-in counts as LATE. */
    @Column(name = "grace_period_minutes", nullable = false)
    @Builder.Default
    private Integer gracePeriodMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "late_deduction_type", nullable = false)
    @Builder.Default
    private DeductionType lateDeductionType = DeductionType.FLAT;

    /** Flat kobo amount or percentage (per {@link #lateDeductionType}) deducted per late day. */
    @Column(name = "late_deduction_value", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal lateDeductionValue = BigDecimal.ZERO;

    /** Free late days per pay period before the deduction starts applying. */
    @Column(name = "late_free_count", nullable = false)
    @Builder.Default
    private Integer lateFreeCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "absence_deduction_type", nullable = false)
    @Builder.Default
    private DeductionType absenceDeductionType = DeductionType.FLAT;

    /** Flat kobo amount or percentage (per {@link #absenceDeductionType}) deducted per absent day. */
    @Column(name = "absence_deduction_value", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal absenceDeductionValue = BigDecimal.ZERO;

    @Column(name = "late_to_absence_conversion_enabled", nullable = false)
    @Builder.Default
    private Boolean lateToAbsenceConversionEnabled = false;

    /** N lates = 1 absence; only consulted when {@link #lateToAbsenceConversionEnabled} is true. */
    @Column(name = "late_to_absence_conversion_count")
    private Integer lateToAbsenceConversionCount;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
