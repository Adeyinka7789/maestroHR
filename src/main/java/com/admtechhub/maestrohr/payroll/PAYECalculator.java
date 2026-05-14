package com.admtechhub.maestrohr.payroll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PAYECalculator {

    // Tax bands for ANNUAL income in kobo (1 NGN = 100 kobo)
    private static final long BAND_1_LIMIT = 30_000_000L;      // NGN 300,000 = 30,000,000 kobo
    private static final long BAND_2_LIMIT = 30_000_000L;      // Next NGN 300,000
    private static final long BAND_3_LIMIT = 50_000_000L;      // Next NGN 500,000
    private static final long BAND_4_LIMIT = 50_000_000L;      // Next NGN 500,000
    private static final long BAND_5_LIMIT = 160_000_000L;     // Next NGN 1,600,000

    private static final double BAND_1_RATE = 0.07;   // 7%
    private static final double BAND_2_RATE = 0.11;   // 11%
    private static final double BAND_3_RATE = 0.15;   // 15%
    private static final double BAND_4_RATE = 0.19;   // 19%
    private static final double BAND_5_RATE = 0.21;   // 21%
    private static final double BAND_6_RATE = 0.24;   // 24%

    private static final long CRA_MINIMUM = 20_000_000L;  // NGN 200,000 = 20,000,000 kobo

    /**
     * Calculate PAYE tax for an employee
     */
    public PAYEResult calculate(Long grossSalary, Long pensionEmployee, Long nhfDeduction, Long basicSalary) {
        // Step 1: Annualize values
        long annualGross = grossSalary * 12;
        long annualPension = pensionEmployee * 12;
        long annualNhf = nhfDeduction * 12;

        // Step 2: Gross Taxable Income = Gross - Pension(Emp) - NHF
        long annualGrossTaxable = annualGross - annualPension - annualNhf;

        // Step 3: Calculate CRA (Consolidated Relief Allowance)
        // Higher of NGN 200,000 OR (1% of Gross + 20% of Gross)
        long craOnePercent = Math.round(annualGross * 0.01);
        long craTwentyPercent = Math.round(annualGross * 0.20);
        long craAlternate = craOnePercent + craTwentyPercent;
        long annualCRA = Math.max(CRA_MINIMUM, craAlternate);

        // Step 4: Taxable Income = Gross Taxable - CRA
        long annualTaxableIncome = annualGrossTaxable - annualCRA;

        // If taxable income is zero or negative, no tax
        if (annualTaxableIncome <= 0) {
            log.debug("No tax payable: AnnualTaxableIncome={}", annualTaxableIncome);
            return PAYEResult.builder()
                    .annualGross(annualGross)
                    .annualGrossTaxable(annualGrossTaxable)
                    .annualCRA(annualCRA)
                    .annualTaxableIncome(0L)
                    .annualPAYE(0L)
                    .monthlyPAYE(0L)
                    .build();
        }

        // Step 5: Calculate PAYE on annual taxable income
        long annualPAYE = calculateProgressiveTax(annualTaxableIncome);

        // Step 6: Monthly PAYE (integer division)
        long monthlyPAYE = annualPAYE / 12;

        log.debug("PAYE Calculation: AnnualGross={}, AnnualTaxable={}, AnnualPAYE={}, MonthlyPAYE={}",
                annualGross, annualTaxableIncome, annualPAYE, monthlyPAYE);

        return PAYEResult.builder()
                .annualGross(annualGross)
                .annualGrossTaxable(annualGrossTaxable)
                .annualCRA(annualCRA)
                .annualTaxableIncome(annualTaxableIncome)
                .annualPAYE(annualPAYE)
                .monthlyPAYE(monthlyPAYE)
                .build();
    }

    /**
     * Apply progressive tax bands to annual taxable income
     */
    private long calculateProgressiveTax(long taxableIncome) {
        long remaining = taxableIncome;
        long totalTax = 0;

        // Band 1: First NGN 300,000 @ 7%
        long band1Amount = Math.min(remaining, BAND_1_LIMIT);
        totalTax += Math.round(band1Amount * BAND_1_RATE);
        remaining -= band1Amount;

        if (remaining <= 0) return totalTax;

        // Band 2: Next NGN 300,000 @ 11%
        long band2Amount = Math.min(remaining, BAND_2_LIMIT);
        totalTax += Math.round(band2Amount * BAND_2_RATE);
        remaining -= band2Amount;

        if (remaining <= 0) return totalTax;

        // Band 3: Next NGN 500,000 @ 15%
        long band3Amount = Math.min(remaining, BAND_3_LIMIT);
        totalTax += Math.round(band3Amount * BAND_3_RATE);
        remaining -= band3Amount;

        if (remaining <= 0) return totalTax;

        // Band 4: Next NGN 500,000 @ 19%
        long band4Amount = Math.min(remaining, BAND_4_LIMIT);
        totalTax += Math.round(band4Amount * BAND_4_RATE);
        remaining -= band4Amount;

        if (remaining <= 0) return totalTax;

        // Band 5: Next NGN 1,600,000 @ 21%
        long band5Amount = Math.min(remaining, BAND_5_LIMIT);
        totalTax += Math.round(band5Amount * BAND_5_RATE);
        remaining -= band5Amount;

        if (remaining <= 0) return totalTax;

        // Band 6: Above NGN 3,200,000 @ 24%
        totalTax += Math.round(remaining * BAND_6_RATE);

        return totalTax;
    }

    @lombok.Builder
    @lombok.Data
    public static class PAYEResult {
        private Long annualGross;
        private Long annualGrossTaxable;
        private Long annualCRA;
        private Long annualTaxableIncome;
        private Long annualPAYE;
        private Long monthlyPAYE;
    }
}