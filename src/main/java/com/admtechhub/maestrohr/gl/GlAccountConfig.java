package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * The tenant's chart-of-accounts codes for the standard payroll journal (see V65) — one row per
 * tenant. Debits: salary/wages expense and employer-pension expense. Credits: net-pay bank
 * liability, and the statutory payables (PAYE, pension, NHF, other deductions). Defaults are
 * seeded on first use by {@code GlExportService}.
 */
@Entity
@Table(name = "gl_account_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@SQLRestriction("tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid")
public class GlAccountConfig extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "salary_expense_account", nullable = false, length = 60)
    @Builder.Default
    private String salaryExpenseAccount = "6000";

    @Column(name = "pension_expense_account", nullable = false, length = 60)
    @Builder.Default
    private String pensionExpenseAccount = "6100";

    @Column(name = "net_pay_account", nullable = false, length = 60)
    @Builder.Default
    private String netPayAccount = "2000";

    @Column(name = "paye_payable_account", nullable = false, length = 60)
    @Builder.Default
    private String payePayableAccount = "2100";

    @Column(name = "pension_payable_account", nullable = false, length = 60)
    @Builder.Default
    private String pensionPayableAccount = "2200";

    @Column(name = "nhf_payable_account", nullable = false, length = 60)
    @Builder.Default
    private String nhfPayableAccount = "2300";

    @Column(name = "other_deductions_account", nullable = false, length = 60)
    @Builder.Default
    private String otherDeductionsAccount = "2400";
}
