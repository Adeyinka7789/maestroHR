package com.admtechhub.maestrohr.payroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for NHFCalculator.
 * NHF = 2.5% of basic salary only. All amounts in kobo.
 */
class NHFCalculatorTest {

    private NHFCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new NHFCalculator();
    }

    // C1: NHF = 2.5% of basic salary
    @Test
    void c1_nhfDeduction_is2point5pctOfBasicSalary() {
        long basicSalary = 50_000_000L; // ₦500,000

        long nhf = calculator.calculate(basicSalary);

        // 50_000_000 × 2.5% = 1_250_000 (₦12,500)
        assertThat(nhf).isEqualTo(1_250_000L);
    }
}
