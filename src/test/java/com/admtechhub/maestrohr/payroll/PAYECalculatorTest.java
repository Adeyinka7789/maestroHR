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
        // Example from handover document: Basic 150k, Housing 60k, Transport 40k
        // Gross monthly: NGN 250,000 = 25,000,000 kobo
        // Pension Employee (8% of 250k) = NGN 20,000 = 2,000,000 kobo
        // NHF (2.5% of Basic 150k) = NGN 3,750 = 375,000 kobo

        Long grossSalary = 25_000_000L;
        Long pensionEmployee = 2_000_000L;
        Long nhfDeduction = 375_000L;
        Long basicSalary = 15_000_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary);

        long monthlyPAYE = result.getMonthlyPAYE();
        assertTrue(monthlyPAYE == 2_715_416L || monthlyPAYE == 2_715_417L,
                "Monthly PAYE should be around 2,715,416-2,715,417 kobo but was: " + monthlyPAYE);

        assertEquals(63_000_000L, result.getAnnualCRA());

        System.out.println("PAYE Test Passed: Monthly PAYE = " + monthlyPAYE + " kobo");
    }

    @Test
    void testLowIncomeNoTax() {
        // Monthly salary NGN 15,000 = 1,500,000 kobo – well below tax threshold
        Long grossSalary = 1_500_000L;        // NGN 15,000
        Long pensionEmployee = 120_000L;       // 8% of 15k = 1,200 = 120,000 kobo
        Long nhfDeduction = 37_500L;           // 2.5% of 15k = 375 = 37,500 kobo
        Long basicSalary = 1_500_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary);

        assertEquals(0L, result.getMonthlyPAYE(), "Income below threshold should pay zero tax");
    }

    @Test
    void testHighIncome() {
        Long grossSalary = 100_000_000L;      // NGN 1,000,000
        Long pensionEmployee = 8_000_000L;
        Long nhfDeduction = 2_500_000L;
        Long basicSalary = 100_000_000L;

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary);

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

        var result = payeCalculator.calculate(grossSalary, pensionEmployee, nhfDeduction, basicSalary);

        assertNotNull(result);
        assertTrue(result.getMonthlyPAYE() >= 0);

        System.out.println("Zero Deductions Test: Monthly PAYE = " + result.getMonthlyPAYE() + " kobo");
    }
}