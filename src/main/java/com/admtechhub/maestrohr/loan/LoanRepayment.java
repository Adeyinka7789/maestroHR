package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * Append-only ledger of loan repayments applied during payroll approval. One row per
 * (loan, payroll run) — the {@code uk_loan_repayment_per_run} unique constraint
 * (V30) makes the "apply" phase idempotent: a re-approval / retry hits the constraint
 * instead of decrementing a loan balance twice.
 *
 * <p>Beyond idempotency it is the audit trail answering "why did this loan go down, and
 * in which payroll run was this amount taken".
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    @JsonIgnoreProperties({"entries", "tenant", "initiatedBy", "approvedBy"})
    private PayrollRun payrollRun;

    /** Amount applied to the loan in this run, in kobo. */
    @Column(name = "amount", nullable = false)
    private Long amount;
}
