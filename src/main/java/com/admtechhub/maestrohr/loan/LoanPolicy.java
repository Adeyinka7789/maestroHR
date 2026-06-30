package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Loan eligibility and limit rules, scoped to a tenant. A policy can be linked
 * to specific {@link com.admtechhub.maestrohr.employee.PayGrade pay grades}; any employee
 * whose grade has no linked policy falls back to the first active policy for the tenant.
 *
 * <p>Soft-deleted via {@code deleted_at}; the {@code @SQLRestriction} excludes deleted rows
 * from all JPA queries, matching the same pattern used for pay grades.
 */
@Entity
@Table(name = "loan_policies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid AND deleted_at IS NULL")
public class LoanPolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** Minimum loan amount the employee may request, in kobo. */
    @Column(name = "min_amount_kobo", nullable = false)
    @Builder.Default
    private Long minAmountKobo = 0L;

    /** Hard ceiling on loan amount, in kobo (also capped by maxMultiplierOfGross). */
    @Column(name = "max_amount_kobo", nullable = false)
    private Long maxAmountKobo;

    /** Max loan = min(maxAmountKobo, maxMultiplierOfGross × grossSalary). */
    @Column(name = "max_multiplier_of_gross", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal maxMultiplierOfGross = new BigDecimal("3.0");

    /** Monthly installment may not exceed this % of gross salary. */
    @Column(name = "max_deduction_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal maxDeductionPct = new BigDecimal("33.0");

    /** After all deductions, employee's net must remain >= this % of gross salary. */
    @Column(name = "net_floor_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal netFloorPct = new BigDecimal("50.0");

    @Column(name = "max_tenor_months", nullable = false)
    @Builder.Default
    private Integer maxTenorMonths = 12;

    @Column(name = "interest_rate_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal interestRatePct = BigDecimal.ZERO;

    /** Days the employee must wait after a completed/cancelled loan before re-applying. */
    @Column(name = "cooling_period_days", nullable = false)
    @Builder.Default
    private Integer coolingPeriodDays = 30;

    /** Maximum number of loan applications (any non-REJECTED status) allowed per calendar year. */
    @Column(name = "max_loans_per_year", nullable = false)
    @Builder.Default
    private Integer maxLoansPerYear = 2;

    /** Loans above this amount require explicit HR/Finance approval (standard flow is already approval-gated). */
    @Column(name = "approval_threshold_kobo", nullable = false)
    @Builder.Default
    private Long approvalThresholdKobo = 10_000_000L;

    /** Employee must have been employed for at least this many months before applying. */
    @Column(name = "eligible_after_months", nullable = false)
    @Builder.Default
    private Integer eligibleAfterMonths = 6;

    /** Whether employees still on probation may apply under this policy. */
    @Column(name = "probation_eligible", nullable = false)
    @Builder.Default
    private Boolean probationEligible = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
