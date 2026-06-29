package com.admtechhub.maestrohr.payroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PensionCalculator.
 * Pension base = Basic + Housing + Transport.
 * Employee rate: 8%, Employer rate: 10%. All amounts in kobo.
 */
class PensionCalculatorTest {

    private PensionCalculator calculator;

    // Pay components (kobo): ₦500k basic, ₦200k housing, ₦100k transport
    private static final long BASIC     = 50_000_000L;
    private static final long HOUSING   = 20_000_000L;
    private static final long TRANSPORT = 10_000_000L;
    private static final long PENSIONABLE = BASIC + HOUSING + TRANSPORT; // 80_000_000

    @BeforeEach
    void setUp() {
        calculator = new PensionCalculator();
    }

    // B1: employee contribution = 8% of pensionable earnings
    @Test
    void b1_employeeContribution_is8pctOfPensionable() {
        PensionCalculator.PensionResult result =
                calculator.calculate(BASIC, HOUSING, TRANSPORT);

        // 80_000_000 × 8% = 6_400_000
        assertThat(result.getPensionableEarnings()).isEqualTo(PENSIONABLE);
        assertThat(result.getEmployeeContribution()).isEqualTo(6_400_000L);
    }

    // B2: employer contribution = 10% of pensionable earnings
    @Test
    void b2_employerContribution_is10pctOfPensionable() {
        PensionCalculator.PensionResult result =
                calculator.calculate(BASIC, HOUSING, TRANSPORT);

        // 80_000_000 × 10% = 8_000_000
        assertThat(result.getPensionableEarnings()).isEqualTo(PENSIONABLE);
        assertThat(result.getEmployerContribution()).isEqualTo(8_000_000L);
    }
}
