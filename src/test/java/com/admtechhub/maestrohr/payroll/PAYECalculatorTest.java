package com.admtechhub.maestrohr.payroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NTA 2025 PAYE band-boundary tests. Real calculator, no mocks. All amounts in kobo.
 *
 * Bands (annual taxable income):
 *   Band 1  first ₦800,000          0 %
 *   Band 2  next  ₦1,400,000       15 %  (ceiling ₦2,200,000)
 *   Band 3  next  ₦2,000,000       18 %  (ceiling ₦4,200,000)
 *   Band 4  next  ₦3,000,000       21 %  (ceiling ₦7,200,000)
 *   Band 5  next  ₦4,000,000       23 %  (ceiling ₦11,200,000)
 *   Band 6  above ₦11,200,000      25 %
 *
 * Min-wage exemption: nominalMonthlyGross ≤ ₦70,000 → zero PAYE regardless of deductions.
 * Tests A3–A8 pass pension=0 / NHF=0 so annualTaxable == annualGross, isolating band logic.
 */
class PAYECalculatorTest {

    private PAYECalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PAYECalculator();
    }

    // A1 ── exactly ₦70,000/month → exempt, zero PAYE on all fields
    @Test
    void a1_exactMinWageBoundary_zeroPaye() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(7_000_000L, 0L, 0L, 7_000_000L, 7_000_000L);

        assertThat(r.getMonthlyPAYE()).isEqualTo(0L);
        assertThat(r.getAnnualPAYE()).isEqualTo(0L);
        assertThat(r.getAnnualTaxableIncome()).isEqualTo(0L);
    }

    // A2 ── one kobo above ₦70,000/month → exemption lifts, Band 1+2 overflow taxed
    //   annualTaxable = 7_000_001 × 12 = 84_000_012
    //   Band 1: 80_000_000 → 0;  remaining = 4_000_012
    //   Band 2: Math.round(4_000_012 × 0.15) = 600_002
    //   monthlyPAYE = 600_002 / 12 = 50_000
    @Test
    void a2_oneKoboAboveMinWage_taxIsComputed() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(7_000_001L, 0L, 0L, 7_000_001L, 7_000_001L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(84_000_012L);
        assertThat(r.getAnnualPAYE()).isEqualTo(600_002L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(50_000L);
    }

    // A3 ── annual taxable < ₦800,000 (fully inside Band 1 free zone) → zero PAYE
    //   nominalMonthlyGross = ₦100,000 → not exempt; grossSalary = ₦60,000 (prorated example)
    //   annualTaxable = 72_000_000 < 80_000_000 → all absorbed by Band 1
    @Test
    void a3_entirelyInBand1FreeZone_zeroPaye() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(6_000_000L, 0L, 0L, 6_000_000L, 10_000_000L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(72_000_000L);
        assertThat(r.getAnnualPAYE()).isEqualTo(0L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(0L);
    }

    // A4 ── taxable income within Band 2 (15%)
    //   grossSalary = ₦150,000/month; annualTaxable = 180_000_000
    //   Band 1: 80M → 0;  Band 2: 100M × 15% = 15_000_000
    //   monthlyPAYE = 15_000_000 / 12 = 1_250_000
    @Test
    void a4_midBand2_15pctApplied() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(15_000_000L, 0L, 0L, 15_000_000L, 15_000_000L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(180_000_000L);
        assertThat(r.getAnnualPAYE()).isEqualTo(15_000_000L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(1_250_000L);
    }

    // A5 ── taxable income spills into Band 3 (18%)
    //   grossSalary = ₦250,000/month; annualTaxable = 300_000_000
    //   Band 1: 0;  Band 2: 21_000_000;  Band 3: 80M × 18% = 14_400_000
    //   annualPAYE = 35_400_000;  monthlyPAYE = 2_950_000
    @Test
    void a5_spillsIntoBand3_18pctApplied() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(25_000_000L, 0L, 0L, 25_000_000L, 25_000_000L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(300_000_000L);
        assertThat(r.getAnnualPAYE()).isEqualTo(35_400_000L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(2_950_000L);
    }

    // A6 ── taxable income spills into Band 4 (21%)
    //   grossSalary = ₦500,000/month; annualTaxable = 600_000_000
    //   Band 1: 0;  Band 2: 21M;  Band 3: 36M;  Band 4: 180M × 21% = 37_800_000
    //   annualPAYE = 94_800_000;  monthlyPAYE = 7_900_000
    @Test
    void a6_spillsIntoBand4_21pctApplied() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(50_000_000L, 0L, 0L, 50_000_000L, 50_000_000L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(600_000_000L);
        assertThat(r.getAnnualPAYE()).isEqualTo(94_800_000L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(7_900_000L);
    }

    // A7 ── taxable income spills into Band 5 (23%)
    //   grossSalary = ₦800,000/month; annualTaxable = 960_000_000
    //   Band 1: 0;  Band 2: 21M;  Band 3: 36M;  Band 4: 63M;  Band 5: 240M × 23% = 55_200_000
    //   annualPAYE = 175_200_000;  monthlyPAYE = 14_600_000
    @Test
    void a7_spillsIntoBand5_23pctApplied() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(80_000_000L, 0L, 0L, 80_000_000L, 80_000_000L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(960_000_000L);
        assertThat(r.getAnnualPAYE()).isEqualTo(175_200_000L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(14_600_000L);
    }

    // A8 ── high earner — Band 6 overflow at 25%
    //   grossSalary = ₦2,000,000/month; annualTaxable = 2_400_000_000
    //   Band 1: 0;  Band 2: 21M;  Band 3: 36M;  Band 4: 63M;  Band 5: 92M
    //   Band 6: 1_280_000_000 × 25% = 320_000_000
    //   annualPAYE = 532_000_000;  monthlyPAYE = 532M / 12 = 44_333_333 (integer div)
    @Test
    void a8_highEarner_band6OverflowAt25pct() {
        PAYECalculator.PAYEResult r =
                calculator.calculate(200_000_000L, 0L, 0L, 200_000_000L, 200_000_000L);

        assertThat(r.getAnnualTaxableIncome()).isEqualTo(2_400_000_000L);
        assertThat(r.getAnnualPAYE()).isEqualTo(532_000_000L);
        assertThat(r.getMonthlyPAYE()).isEqualTo(44_333_333L);
    }
}
