package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payroll_entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class PayrollEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    @JsonIgnoreProperties({"createdAt", "updatedAt", "active", "subscriptionPlan", "subscriptionExpiresAt"})
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    @JsonIgnoreProperties({"entries", "tenant", "initiatedBy", "approvedBy"})
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    @JsonIgnoreProperties({"tenant", "user", "department", "payGrade", "ninEncrypted", "bvnEncrypted"})
    private Employee employee;

    @Column(name = "basic_salary", nullable = false)
    private Long basicSalary;

    @Column(name = "housing_allowance", nullable = false)
    private Long housingAllowance;

    @Column(name = "transport_allowance", nullable = false)
    private Long transportAllowance;

    @Column(name = "other_allowances", nullable = false)
    private Long otherAllowances;

    @Column(name = "gross_salary", nullable = false)
    private Long grossSalary;

    @Column(name = "pension_employee", nullable = false)
    private Long pensionEmployee;

    @Column(name = "pension_employer", nullable = false)
    private Long pensionEmployer;

    @Column(name = "nhf_deduction", nullable = false)
    private Long nhfDeduction;

    @Column(name = "paye_tax", nullable = false)
    private Long payeTax;

    @Column(name = "other_deductions", nullable = false)
    @Builder.Default
    private Long otherDeductions = 0L;

    /** One-off TAXABLE earning adjustments added to gross this period (kobo). */
    @Column(name = "taxable_earnings", nullable = false)
    @Builder.Default
    private Long taxableEarnings = 0L;

    /** One-off NON_TAXABLE earning adjustments (reimbursements) added to gross this period (kobo). */
    @Column(name = "non_taxable_earnings", nullable = false)
    @Builder.Default
    private Long nonTaxableEarnings = 0L;

    /** One-off PRE_TAX deduction adjustments (voluntary pension etc.) this period (kobo). */
    @Column(name = "pretax_deduction", nullable = false)
    @Builder.Default
    private Long pretaxDeduction = 0L;

    /** One-off POST_TAX deduction adjustments (fines, advances) applied this period (kobo). */
    @Column(name = "adjustment_deduction", nullable = false)
    @Builder.Default
    private Long adjustmentDeduction = 0L;

    /** TRUE when post-tax adjustment deductions were reduced by the net-floor protection. */
    @Column(name = "adjustment_capped", nullable = false)
    @Builder.Default
    private Boolean adjustmentCapped = false;

    @Column(name = "unpaid_leave_deduction", nullable = false)
    @Builder.Default
    private Long unpaidLeaveDeduction = 0L;

    @Column(name = "attendance_deduction", nullable = false)
    @Builder.Default
    private Long attendanceDeduction = 0L;

    /** Late-arrival deduction for the period (kobo), from the effective AttendancePolicy — separate from attendanceDeduction (absence-only). */
    @Column(name = "late_deduction", nullable = false)
    @Builder.Default
    private Long lateDeduction = 0L;

    /** Active-loan repayment deducted this period (kobo) — post-statutory line item. */
    @Column(name = "loan_deduction", nullable = false)
    @Builder.Default
    private Long loanDeduction = 0L;

    /** TRUE when the loan deduction was reduced by the net-floor protection in PayrollEngine. */
    @Column(name = "loan_deduction_capped", nullable = false)
    @Builder.Default
    private Boolean loanDeductionCapped = false;

    /** TRUE when the platform-wide minimum-wage net floor / non-negative clamp reduced the
     *  loan deduction or floored net salary at 0 for this entry — independent of whether a
     *  LoanPolicy is configured (unlike loanDeductionCapped alone, which only fires under a
     *  configured policy's stricter floor). */
    @Column(name = "net_floor_clamped", nullable = false)
    @Builder.Default
    private Boolean netFloorClamped = false;

    /** Compute-time snapshot of "unpaidLeaveDays:absentDays:lateDays:loanDeduction" for this
     *  entry's employee/period — re-verified in full at approval to detect drift. Null for
     *  entries computed before this guard existed. */
    @Column(name = "deduction_snapshot", length = 100)
    private String deductionSnapshot;

    /** LATE records in the period — informational, not deducted automatically. */
    @Column(name = "late_days_in_period", nullable = false)
    @Builder.Default
    private Integer lateDaysInPeriod = 0;

    @Column(name = "net_salary", nullable = false)
    private Long netSalary;

    @Column(name = "days_worked", nullable = false)
    private Integer daysWorked;

    @Column(name = "working_days", nullable = false)
    private Integer workingDays;

    @Column(name = "is_prorated", nullable = false)
    @Builder.Default
    private Boolean isProrated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_status", nullable = false)
    @Builder.Default
    private TransferStatus transferStatus = TransferStatus.PENDING;

    @Column(name = "transfer_reference")
    private String transferReference;

    @Column(name = "payslip_generated", nullable = false)
    @Builder.Default
    private Boolean payslipGenerated = false;

    @Column(name = "paystack_transfer_code", length = 100)
    private String paystackTransferCode;

    // Getter and setter
    public String getPaystackTransferCode() {
        return paystackTransferCode;
    }

    public void setPaystackTransferCode(String paystackTransferCode) {
        this.paystackTransferCode = paystackTransferCode;
    }
}