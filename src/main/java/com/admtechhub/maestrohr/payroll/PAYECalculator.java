package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PAYE calculator under the Nigeria Tax Act 2025 regime (effective 1 Jan 2026).
 *
 * Key differences from the old Finance Act regime:
 *  - No Consolidated Relief Allowance (CRA).
 *  - First ₦800,000/yr of taxable income is tax-free, then progressive bands.
 *  - Rent Relief: 20% of annual rent paid, capped at ₦500,000/yr.
 *  - Minimum-wage exemption: ≤ ₦70,000/month → no PAYE.
 *
 * All amounts are in kobo (1 NGN = 100 kobo); integer math throughout.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PAYECalculator {

    private final PlatformSettingsService platformSettingsService;

    // Fallback values used only when the platform_settings row is missing or unparseable —
    // see PlatformSettingsService#getLongOrDefault / #getDoubleOrDefault.
    // Minimum-wage exemption: employees earning <= the threshold pay no PAYE. Tested against
    // NOMINAL monthly pay (pre-proration) so a higher earner who worked a partial month is
    // still taxed on their prorated gross.
    private static final long MIN_WAGE_EXEMPTION_MONTHLY_DEFAULT = 7_000_000L;   // ₦70,000

    // Rent Relief: a percentage of annual rent paid, capped at an annual ceiling.
    private static final double RENT_RELIEF_RATE_PCT_DEFAULT = 20.0;            // 20%
    private static final long   RENT_RELIEF_CAP_KOBO_DEFAULT = 50_000_000L;     // ₦500,000

    // Progressive band widths for ANNUAL taxable income (kobo) — not yet settings-backed;
    // needs a dedicated effective-dated band table, tracked as a separate follow-up.
    private static final long BAND_TAX_FREE = 80_000_000L;    // first ₦800,000 @ 0%
    private static final long BAND_2_WIDTH  = 140_000_000L;   // next ₦1,400,000 (→ ₦2,200,000)
    private static final long BAND_3_WIDTH  = 200_000_000L;   // next ₦2,000,000 (→ ₦4,200,000)
    private static final long BAND_4_WIDTH  = 300_000_000L;   // next ₦3,000,000 (→ ₦7,200,000)
    private static final long BAND_5_WIDTH  = 400_000_000L;   // next ₦4,000,000 (→ ₦11,200,000)
    // remainder above ₦11,200,000 @ 25%

    private static final double BAND_2_RATE = 0.15;
    private static final double BAND_3_RATE = 0.18;
    private static final double BAND_4_RATE = 0.21;
    private static final double BAND_5_RATE = 0.23;
    private static final double BAND_6_RATE = 0.25;

    /**
     * Calculate PAYE for an employee.
     *
     * @param grossSalary         monthly gross actually earned this period (post-proration), kobo
     * @param pensionEmployee     monthly employee pension contribution, kobo
     * @param nhfDeduction        monthly NHF deduction, kobo
     * @param basicSalary         monthly basic salary, kobo (retained for signature stability)
     * @param nominalMonthlyGross full monthly gross before proration, kobo — used only for the
     *                            minimum-wage exemption test, never for the tax computation itself
     */
    public PAYEResult calculate(Long grossSalary, Long pensionEmployee, Long nhfDeduction,
                                Long basicSalary, Long nominalMonthlyGross) {
        long annualGross = grossSalary * 12;

        long minWageExemptionMonthly = platformSettingsService.getLongOrDefault(
                "min_wage_kobo", MIN_WAGE_EXEMPTION_MONTHLY_DEFAULT);

        // Step 0: Minimum-wage exemption — judged on nominal (un-prorated) monthly pay.
        if (nominalMonthlyGross <= minWageExemptionMonthly) {
            log.debug("Min-wage exempt: nominalMonthlyGross={} <= {}",
                    nominalMonthlyGross, minWageExemptionMonthly);
            return zeroResult(annualGross);
        }

        // Step 1: Annualize statutory deductions
        long annualPension = pensionEmployee * 12;
        long annualNhf = nhfDeduction * 12;

        // Step 2: Rent Relief = a settings-configured % of annual rent paid, capped at a
        // settings-configured ceiling. TODO: source annual rent paid from the employee
        // profile (follow-up). 0 for now, so this branch is currently a no-op in practice.
        double rentReliefRate = platformSettingsService.getDoubleOrDefault(
                "rent_relief_rate_pct", RENT_RELIEF_RATE_PCT_DEFAULT) / 100.0;
        long rentReliefCap = platformSettingsService.getLongOrDefault(
                "rent_relief_cap_kobo", RENT_RELIEF_CAP_KOBO_DEFAULT);
        long annualRentPaid = 0L;
        long annualRentRelief = Math.min(Math.round(annualRentPaid * rentReliefRate), rentReliefCap);

        // Step 3: Taxable income = gross − pension − NHF − rent relief
        long annualGrossTaxable = annualGross - annualPension - annualNhf;
        long annualTaxableIncome = annualGrossTaxable - annualRentRelief;

        if (annualTaxableIncome <= 0) {
            log.debug("No tax payable: annualTaxableIncome={}", annualTaxableIncome);
            return PAYEResult.builder()
                    .annualGross(annualGross)
                    .annualGrossTaxable(annualGrossTaxable)
                    .annualRentRelief(annualRentRelief)
                    .annualTaxableIncome(0L)
                    .annualPAYE(0L)
                    .monthlyPAYE(0L)
                    .build();
        }

        // Step 4: Progressive tax, then monthly PAYE (integer division)
        long annualPAYE = calculateProgressiveTax(annualTaxableIncome);
        long monthlyPAYE = annualPAYE / 12;

        log.debug("PAYE (NTA 2025): annualGross={}, annualTaxable={}, annualPAYE={}, monthlyPAYE={}",
                annualGross, annualTaxableIncome, annualPAYE, monthlyPAYE);

        return PAYEResult.builder()
                .annualGross(annualGross)
                .annualGrossTaxable(annualGrossTaxable)
                .annualRentRelief(annualRentRelief)
                .annualTaxableIncome(annualTaxableIncome)
                .annualPAYE(annualPAYE)
                .monthlyPAYE(monthlyPAYE)
                .build();
    }

    private PAYEResult zeroResult(long annualGross) {
        return PAYEResult.builder()
                .annualGross(annualGross)
                .annualGrossTaxable(0L)
                .annualRentRelief(0L)
                .annualTaxableIncome(0L)
                .annualPAYE(0L)
                .monthlyPAYE(0L)
                .build();
    }

    /**
     * Apply the NTA 2025 progressive bands to annual taxable income (kobo).
     */
    private long calculateProgressiveTax(long taxableIncome) {
        long remaining = taxableIncome;
        long totalTax = 0;

        // Band 1: first ₦800,000 @ 0%
        long band1 = Math.min(remaining, BAND_TAX_FREE);
        remaining -= band1;
        if (remaining <= 0) return totalTax;

        // Band 2: next ₦1,400,000 @ 15%
        long band2 = Math.min(remaining, BAND_2_WIDTH);
        totalTax += Math.round(band2 * BAND_2_RATE);
        remaining -= band2;
        if (remaining <= 0) return totalTax;

        // Band 3: next ₦2,000,000 @ 18%
        long band3 = Math.min(remaining, BAND_3_WIDTH);
        totalTax += Math.round(band3 * BAND_3_RATE);
        remaining -= band3;
        if (remaining <= 0) return totalTax;

        // Band 4: next ₦3,000,000 @ 21%
        long band4 = Math.min(remaining, BAND_4_WIDTH);
        totalTax += Math.round(band4 * BAND_4_RATE);
        remaining -= band4;
        if (remaining <= 0) return totalTax;

        // Band 5: next ₦4,000,000 @ 23%
        long band5 = Math.min(remaining, BAND_5_WIDTH);
        totalTax += Math.round(band5 * BAND_5_RATE);
        remaining -= band5;
        if (remaining <= 0) return totalTax;

        // Band 6: above ₦11,200,000 @ 25%
        totalTax += Math.round(remaining * BAND_6_RATE);
        return totalTax;
    }

    @lombok.Builder
    @lombok.Data
    public static class PAYEResult {
        private Long annualGross;
        private Long annualGrossTaxable;
        private Long annualRentRelief;
        private Long annualTaxableIncome;
        private Long annualPAYE;
        private Long monthlyPAYE;
    }
}
