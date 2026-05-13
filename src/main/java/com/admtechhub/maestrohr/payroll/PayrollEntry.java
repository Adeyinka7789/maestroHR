package com.admtechhub.maestrohr.payroll;

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

@Entity
@Table(name = "payroll_entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
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
}