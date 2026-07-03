package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NSITFCalculator {

    private final PlatformSettingsService platformSettingsService;

    // Fallback used only when the platform_settings row is missing or unparseable.
    private static final double NSITF_RATE_PCT_DEFAULT = 1.0;  // 1% of gross (employer only)

    /**
     * Nigeria Social Insurance Trust Fund: settings-configured % of gross salary (Employer only)
     * @param grossSalary in kobo
     * @return NSITF contribution in kobo (not deducted from employee)
     */
    public Long calculateEmployerContribution(Long grossSalary) {
        double nsitfRate = platformSettingsService.getDoubleOrDefault("nsitf_rate_pct", NSITF_RATE_PCT_DEFAULT) / 100.0;
        Long nsitf = Math.round(grossSalary * nsitfRate);
        log.debug("NSITF Employer: Gross={}, Contribution={}", grossSalary, nsitf);
        return nsitf;
    }
}