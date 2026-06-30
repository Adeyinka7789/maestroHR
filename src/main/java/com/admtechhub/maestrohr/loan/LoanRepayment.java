package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * Append-only ledger of loan repayments. One STANDARD row per (loan, payroll run) —
 * the {@code uk_loan_repayment_per_run} unique constraint (V30) makes the apply phase
 * idempotent. WAIVER rows have {@code payrollRun = null} and are created by
 * {@link LoanService#waiveLoan} when HR/Finance writes off a remaining balance.
 */
@Entity
@Table(name = "loan_repayments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class LoanRepayment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id", nullable = false)
    @JsonIgnoreProperties({"tenant", "employee"})
    private EmployeeLoan loan;

    /** Null for WAIVER rows — waivers have no associated payroll run. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = true)
    @JsonIgnoreProperties({"entries", "tenant", "initiatedBy", "approvedBy"})
    private PayrollRun payrollRun;

    /** Amount applied to the loan in this row, in kobo. */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_type", nullable = false, length = 20)
    @Builder.Default
    private RepaymentType repaymentType = RepaymentType.STANDARD;

    /** Set when this repayment row was voided by a payroll reversal. Null means active. */
    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;
}
