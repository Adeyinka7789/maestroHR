package com.admtechhub.maestrohr.payroll;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeductionResult {
    private Long grossSalary;           // in kobo
    private Long pensionEmployee;       // 8% of pensionable (kobo)
    private Long pensionEmployer;       // 10% of pensionable (kobo)
    private Long nhfDeduction;          // 2.5% of basic (kobo)
    private Long nsitfEmployer;         // 1% of gross (kobo)
    private Long payeTax;               // Computed PAYE (kobo)
    private Long netSalary;             // Gross - deductions (kobo)

    // Consolidated Relief Allowance components
    private Long cra;                   // Higher of 200k or (1%+20% of gross)
    private Long taxableIncome;         // After CRA
}