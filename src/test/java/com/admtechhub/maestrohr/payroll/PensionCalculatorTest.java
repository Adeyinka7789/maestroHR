package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PensionCalculator.
 * Pension base = Basic + Housing + Transport.
 * Employee rate: 8%, Employer rate: 10% (platform_settings defaults). All amounts in kobo.
 */
class PensionCalculatorTest {

    private PlatformSettingsService platformSettingsService;
    private PensionCalculator calculator;

    // Pay components (kobo): ₦500k basic, ₦200k housing, ₦100k transport
    private static final long BASIC     = 50_000_000L;
    private static final long HOUSING   = 20_000_000L;
    private static final long TRANSPORT = 10_000_000L;
    private static final long PENSIONABLE = BASIC + HOUSING + TRANSPORT; // 80_000_000

    @BeforeEach
    void setUp() {
        // Passthrough stub: any key falls back to the caller's default unless a test
        // overrides that specific key below — mirrors "no settings row configured".
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getLongOrDefault(anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(platformSettingsService.getDoubleOrDefault(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));
        calculator = new PensionCalculator(platformSettingsService);
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

    // B3 ── platform_settings override for pension_employee_pct changes the employee rate,
    // proving the calculator reads config rather than the hardcoded 8% constant.
    @Test
    void b3_employeeRateOverride_9pctInsteadOfHardcoded8pct() {
        when(platformSettingsService.getDoubleOrDefault(eq("pension_employee_pct"), anyDouble()))
                .thenReturn(9.0);

        PensionCalculator.PensionResult result =
                calculator.calculate(BASIC, HOUSING, TRANSPORT);

        // 80_000_000 × 9% = 7_200_000 (would be 6_400_000 at the old hardcoded 8%)
        assertThat(result.getEmployeeContribution()).isEqualTo(7_200_000L);
    }
}
