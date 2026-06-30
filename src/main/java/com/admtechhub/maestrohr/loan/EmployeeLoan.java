package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An employee loan or salary advance, repaid via a fixed monthly installment deducted
 * during payroll. All money fields are in kobo (Long), matching the rest of the engine.
 *
 * <p>Tenant-scoped via the {@code @SQLRestriction} (the V20/V30 RLS policy enforces the
 * same isolation at the database for the {@code maestro_app} role).
 *
 * <p>The deduction applied in a payroll cycle is {@code monthly_installment}, capped at
 * {@code remaining_balance}, except on the final scheduled month where the entire
 * remaining balance is taken so integer-division rounding never leaves a few stray kobo
 * outstanding — see {@link LoanService#installmentFor}.
 */
@Entity
@Table(name = "employee_loans")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class EmployeeLoan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    @JsonIgnoreProperties({"tenant", "user", "department", "payGrade", "ninEncrypted", "bvnEncrypted"})
    private Employee employee;

    /** Total principal advanced, in kobo. */
    @Column(name = "loan_amount", nullable = false)
    private Long loanAmount;

    /** Scheduled deduction per payroll cycle, in kobo (loan_amount / repayment_months). */
    @Column(name = "monthly_installment", nullable = false)
    private Long monthlyInstallment;

    /** Outstanding balance in kobo; decreases as repayments are applied. */
    @Column(name = "remaining_balance", nullable = false)
    private Long remainingBalance;

    @Column(name = "repayment_months", nullable = false)
    private Integer repaymentMonths;

    @Column(name = "months_paid", nullable = false)
    @Builder.Default
    private Integer monthsPaid = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private LoanStatus status = LoanStatus.PENDING;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "description", length = 500)
    private String description;

    /** Email of the applicant — the employee who requested the loan. */
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** Email of the HR/Finance user who approved or rejected the request; null while PENDING. */
    @Column(name = "decided_by")
    private String decidedBy;

    /** When the approve/reject decision was made; null while PENDING. */
    @Column(name = "decided_at")
    private java.time.OffsetDateTime decidedAt;

    /** Reason captured when the request is rejected. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** Interest rate from the policy at the time the loan was approved; 0.0 = interest-free. */
    @Column(name = "interest_rate_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal interestRatePct = BigDecimal.ZERO;

    /** Snapshot of the policy that governed this loan at application time; may be null for legacy loans. */
    @Column(name = "loan_policy_id")
    private UUID loanPolicyId;

    /** Free-text reason recorded when an HR/Finance user writes off the remaining balance. */
    @Column(name = "waiver_reason", length = 500)
    private String waiverReason;

    @Column(name = "waived_at")
    private java.time.OffsetDateTime waivedAt;

    /** Email of the HR/Finance user who granted the waiver. */
    @Column(name = "waived_by")
    private String waivedBy;
}
