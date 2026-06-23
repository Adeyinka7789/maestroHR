package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.PayGrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollEngine {

    private final PensionCalculator pensionCalculator;
    private final NHFCalculator nhfCalculator;
    private final NSITFCalculator nsitfCalculator;
    private final PAYECalculator payeCalculator;

    /**
     * Calculate complete payroll for a single employee.
     *
     * @param employee        Employee with pay grade
     * @param daysWorked      Days worked in month (for mid-month joiner proration)
     * @param workingDays     Total working days in month
     * @param unpaidLeaveDays Approved unpaid leave days in the period — deducted post-statutory
     * @param absentDays      ABSENT attendance records in the period — deducted post-statutory
     * @param loanDeduction   Active-loan repayment for the period (kobo) — deducted post-statutory.
     *                        Calculated by LoanService and passed in; the engine only nets it out.
     * @return Complete PayrollResult including separate deduction line items
     */
    public PayrollResult calculateEmployeePayroll(Employee employee, int daysWorked, int workingDays,
                                                  int unpaidLeaveDays, int absentDays, long loanDeduction) {
        PayGrade payGrade = employee.getPayGrade();

        // Get base salaries from pay grade (all in kobo)
        Long basicSalary = payGrade.getBasicSalary();
        Long housingAllowance = payGrade.getHousingAllowance();
        Long transportAllowance = payGrade.getTransportAllowance();
        Long otherAllowances = payGrade.getOtherAllowances();

        // Nominal (un-prorated) monthly gross — used only for the PAYE minimum-wage
        // exemption test, so a higher earner who worked a partial month is still taxed.
        Long nominalMonthlyGross = basicSalary + housingAllowance + transportAllowance + otherAllowances;

        // Apply proration if days worked < working days
        double prorationFactor = (double) daysWorked / workingDays;
        boolean isProrated = daysWorked < workingDays;

        if (isProrated) {
            basicSalary = Math.round(basicSalary * prorationFactor);
            housingAllowance = Math.round(housingAllowance * prorationFactor);
            transportAllowance = Math.round(transportAllowance * prorationFactor);
            otherAllowances = Math.round(otherAllowances * prorationFactor);
            log.debug("Prorated salary for {}: {} days out of {} (factor: {})",
                    employee.getFullName(), daysWorked, workingDays, prorationFactor);
        }

        // Step 1: Calculate Gross Salary
        Long grossSalary = basicSalary + housingAllowance + transportAllowance + otherAllowances;

        // Step 2: Calculate Pension (on Basic + Housing + Transport)
        var pensionResult = pensionCalculator.calculate(basicSalary, housingAllowance, transportAllowance);

        // Step 3: Calculate NHF (on Basic only)
        Long nhfDeduction = nhfCalculator.calculate(basicSalary);

        // Step 4: Calculate PAYE
        var payeResult = payeCalculator.calculate(grossSalary, pensionResult.getEmployeeContribution(), nhfDeduction, basicSalary, nominalMonthlyGross);

        // Step 5: Calculate NSITF (Employer only)
        Long nsitfEmployer = nsitfCalculator.calculateEmployerContribution(grossSalary);

        // Step 6: Calculate post-statutory deductions (unpaid leave + unexcused absence).
        // Daily rate uses integer division — no floating point; small rounding difference is
        // acceptable and consistent with how proration is applied elsewhere in this engine.
        Long dailyRateKobo       = grossSalary / workingDays;
        Long unpaidLeaveDeduction = dailyRateKobo * unpaidLeaveDays;
        Long attendanceDeduction  = dailyRateKobo * absentDays;

        // Step 7: Calculate Net Salary. Loan repayment is post-tax (Nigerian loan
        // repayments don't reduce taxable income), so it nets out alongside the other
        // post-statutory deductions.
        Long statutoryDeductions = pensionResult.getEmployeeContribution() + nhfDeduction + payeResult.getMonthlyPAYE();
        Long netSalary = grossSalary - statutoryDeductions - unpaidLeaveDeduction - attendanceDeduction - loanDeduction;

        log.info("Payroll complete for {}: Gross={}, Net={}, PAYE={}, Pension={}, NHF={}, UnpaidLeave={}, Absent={}, Loan={}",
                employee.getFullName(), grossSalary, netSalary, payeResult.getMonthlyPAYE(),
                pensionResult.getEmployeeContribution(), nhfDeduction, unpaidLeaveDeduction, attendanceDeduction, loanDeduction);

        return PayrollResult.builder()
                .employeeId(employee.getId())
                .employeeName(employee.getFullName())
                .employeeNumber(employee.getEmployeeNumber())
                .basicSalary(basicSalary)
                .housingAllowance(housingAllowance)
                .transportAllowance(transportAllowance)
                .otherAllowances(otherAllowances)
                .grossSalary(grossSalary)
                .pensionEmployee(pensionResult.getEmployeeContribution())
                .pensionEmployer(pensionResult.getEmployerContribution())
                .nhfDeduction(nhfDeduction)
                .nsitfEmployer(nsitfEmployer)
                .payeTax(payeResult.getMonthlyPAYE())
                .otherDeductions(0L)
                .unpaidLeaveDeduction(unpaidLeaveDeduction)
                .attendanceDeduction(attendanceDeduction)
                .loanDeduction(loanDeduction)
                .netSalary(netSalary)
                .daysWorked(daysWorked)
                .workingDays(workingDays)
                .isProrated(isProrated)
                .taxableIncome(payeResult.getAnnualTaxableIncome())
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class PayrollResult {
        private UUID employeeId;
        private String employeeName;
        private String employeeNumber;
        private Long basicSalary;
        private Long housingAllowance;
        private Long transportAllowance;
        private Long otherAllowances;
        private Long grossSalary;
        private Long pensionEmployee;
        private Long pensionEmployer;
        private Long nhfDeduction;
        private Long nsitfEmployer;
        private Long payeTax;
        private Long otherDeductions;
        private Long unpaidLeaveDeduction;
        private Long attendanceDeduction;
        private Long loanDeduction;
        private Long netSalary;
        private Integer daysWorked;
        private Integer workingDays;
        private Boolean isProrated;
        private Long taxableIncome;
    }
}