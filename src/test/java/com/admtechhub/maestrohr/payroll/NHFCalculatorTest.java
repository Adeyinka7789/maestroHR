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
 * Unit tests for NHFCalculator.
 * NHF = 2.5% of basic salary only (platform_settings default). All amounts in kobo.
 */
class NHFCalculatorTest {

    private PlatformSettingsService platformSettingsService;
    private NHFCalculator calculator;

    @BeforeEach
    void setUp() {
        // Passthrough stub: any key falls back to the caller's default unless a test
        // overrides that specific key below — mirrors "no settings row configured".
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getLongOrDefault(anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(platformSettingsService.getDoubleOrDefault(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));
        calculator = new NHFCalculator(platformSettingsService);
    }

    // C1: NHF = 2.5% of basic salary
    @Test
    void c1_nhfDeduction_is2point5pctOfBasicSalary() {
        long basicSalary = 50_000_000L; // ₦500,000

        long nhf = calculator.calculate(basicSalary);

        // 50_000_000 × 2.5% = 1_250_000 (₦12,500)
        assertThat(nhf).isEqualTo(1_250_000L);
    }

    // C2 ── platform_settings override for nhf_rate_pct changes the deduction rate,
    // proving the calculator reads config rather than the hardcoded 2.5% constant.
    @Test
    void c2_rateOverride_3pctInsteadOfHardcoded2point5pct() {
        when(platformSettingsService.getDoubleOrDefault(eq("nhf_rate_pct"), anyDouble()))
                .thenReturn(3.0);

        long basicSalary = 50_000_000L; // ₦500,000
        long nhf = calculator.calculate(basicSalary);

        // 50_000_000 × 3% = 1_500_000 (would be 1_250_000 at the old hardcoded 2.5%)
        assertThat(nhf).isEqualTo(1_500_000L);
    }
}
