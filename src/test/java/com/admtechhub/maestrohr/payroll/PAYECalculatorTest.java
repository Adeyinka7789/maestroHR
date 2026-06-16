package com.admtechhub.maestrohr.payroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PAYECalculatorTest {

    private PAYECalculator payeCalculator;

    @BeforeEach
    void setUp() {
        payeCalculator = new PAYECalculator();
    }

    @Test
    void testExampleFromDocumentation() {
        // NTA 2025 worked example: gross NGN 250,000/month = 25,000,000 kobo
        // Pension Employee (8% of 250k) = NGN 20,000 = 2,000,000 kobo
        // NHF (2.5% of Basic 150k)      = NGN 3,750  = 375,000 kobo
        //
        // Annual gross    = 300,000,000 kobo
        // Annual pension  =  24,000,000 kobo
        // Annual NHF      =   4,500,000 kobo
        // Rent relief     =           0 kobo
        // Annual taxable  = 271,500,000 kobo
        //   first ₦800,000 @ 0%           = 0
        //   next  ₦1,400,000 @ 15%        = 21,000,000
        //   next  ₦515,000 (of ₦2m) @ 18% =  9,270,000
        // Annual PAYE     =  30,270,000 kobo → Monthly = 2,522,500 kobo (₦25,225)

        Long grossSalary = 25_000_000L;
        Long pensionEmployee = 2_000_000L;
        Long nhfDeduction = 375_000L;
        Long basicSalary = 15_000_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary, grossSalary);

        assertEquals(271_500_000L, result.getAnnualTaxableIncome(), "annual taxable income (kobo)");
        assertEquals(2_522_500L, result.getMonthlyPAYE(), "monthly PAYE under NTA 2025 (kobo)");

        System.out.println("PAYE Test Passed: Monthly PAYE = " + result.getMonthlyPAYE() + " kobo");
    }

    @Test
    void testLowIncomeNoTax() {
        // NGN 15,000/month — below the ₦70,000 minimum-wage exemption ceiling → 0 PAYE
        Long grossSalary = 1_500_000L;        // NGN 15,000
        Long pensionEmployee = 120_000L;       // 8% of 15k = 1,200 = 120,000 kobo
        Long nhfDeduction = 37_500L;           // 2.5% of 15k = 375 = 37,500 kobo
        Long basicSalary = 1_500_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary, grossSalary);

        assertEquals(0L, result.getMonthlyPAYE(), "Income below exemption ceiling should pay zero tax");
    }

    @Test
    void testMinimumWageExemptionBoundary() {
        // At exactly ₦70,000/month nominal → exempt
        var exempt = payeCalculator.calculate(7_000_000L, 0L, 0L, 7_000_000L, 7_000_000L);
        assertEquals(0L, exempt.getMonthlyPAYE(), "₦70,000/month is at the exemption ceiling → no PAYE");

        // Just above the ceiling → taxed
        var taxed = payeCalculator.calculate(7_100_000L, 0L, 0L, 7_100_000L, 7_100_000L);
        assertTrue(taxed.getMonthlyPAYE() > 0, "Above ₦70,000/month → PAYE applies");
    }

    @Test
    void testExemptionUsesNominalNotProratedGross() {
        // Nominal ₦138,000/month, but only ₦69,000 earned this month (partial month).
        // The prorated monthly pay (₦69k) sits below the ₦70k ceiling, but the nominal
        // pay (₦138k) is above it → the employee must still be taxed on the prorated gross.
        var result = payeCalculator.calculate(6_900_000L, 0L, 0L, 6_900_000L, 13_800_000L);
        assertTrue(result.getMonthlyPAYE() > 0,
                "Exemption must be judged on nominal monthly pay, not prorated earnings");
    }

    @Test
    void testHighIncome() {
        Long grossSalary = 100_000_000L;      // NGN 1,000,000
        Long pensionEmployee = 8_000_000L;
        Long nhfDeduction = 2_500_000L;
        Long basicSalary = 100_000_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary, grossSalary);

        assertTrue(result.getMonthlyPAYE() > 0, "High income should pay positive tax");
        assertTrue(result.getMonthlyPAYE() < grossSalary, "Tax should be less than gross salary");

        System.out.println("High Income Test: Gross=" + grossSalary +
                ", PAYE=" + result.getMonthlyPAYE() +
                ", Net=" + (grossSalary - result.getMonthlyPAYE()));
    }

    @Test
    void testZeroDeductions() {
        Long grossSalary = 25_000_000L;
        Long pensionEmployee = 0L;
        Long nhfDeduction = 0L;
        Long basicSalary = 15_000_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary, grossSalary);

        assertNotNull(result);
        assertTrue(result.getMonthlyPAYE() >= 0);

        System.out.println("Zero Deductions Test: Monthly PAYE = " + result.getMonthlyPAYE() + " kobo");
    }
}