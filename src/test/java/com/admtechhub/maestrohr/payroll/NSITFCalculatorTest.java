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
 * Unit tests for NSITFCalculator.
 * NSITF = 1% of gross salary (employer only, not deducted from employee — platform_settings
 * default). All amounts in kobo.
 */
class NSITFCalculatorTest {

    private PlatformSettingsService platformSettingsService;
    private NSITFCalculator calculator;

    @BeforeEach
    void setUp() {
        // Passthrough stub: any key falls back to the caller's default unless a test
        // overrides that specific key below — mirrors "no settings row configured".
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getLongOrDefault(anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(platformSettingsService.getDoubleOrDefault(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));
        calculator = new NSITFCalculator(platformSettingsService);
    }

    // D1: NSITF = 1% of gross salary (default rate)
    @Test
    void d1_employerContribution_is1pctOfGrossSalary() {
        long grossSalary = 50_000_000L; // ₦500,000

        long nsitf = calculator.calculateEmployerContribution(grossSalary);

        // 50_000_000 × 1% = 500_000 (₦5,000)
        assertThat(nsitf).isEqualTo(500_000L);
    }

    // D2 ── platform_settings override for nsitf_rate_pct changes the contribution rate,
    // proving the calculator reads config rather than the hardcoded 1% constant.
    @Test
    void d2_rateOverride_2pctInsteadOfHardcoded1pct() {
        when(platformSettingsService.getDoubleOrDefault(eq("nsitf_rate_pct"), anyDouble()))
                .thenReturn(2.0);

        long grossSalary = 50_000_000L; // ₦500,000
        long nsitf = calculator.calculateEmployerContribution(grossSalary);

        // 50_000_000 × 2% = 1_000_000 (would be 500_000 at the old hardcoded 1%)
        assertThat(nsitf).isEqualTo(1_000_000L);
    }
}
