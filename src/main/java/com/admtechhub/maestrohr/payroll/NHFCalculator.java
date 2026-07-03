package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NHFCalculator {

    private final PlatformSettingsService platformSettingsService;

    // Fallback used only when the platform_settings row is missing or unparseable.
    private static final double NHF_RATE_PCT_DEFAULT = 2.5;  // 2.5% of basic salary

    /**
     * National Housing Fund: settings-configured % of basic salary
     * @param basicSalary in kobo
     * @return NHF deduction in kobo
     */
    public Long calculate(Long basicSalary) {
        double nhfRate = platformSettingsService.getDoubleOrDefault("nhf_rate_pct", NHF_RATE_PCT_DEFAULT) / 100.0;
        Long nhf = Math.round(basicSalary * nhfRate);
        log.debug("NHF: Basic={}, NHF Deduction={}", basicSalary, nhf);
        return nhf;
    }
}