package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class PensionCalculator {

    private final PlatformSettingsService platformSettingsService;

    // Fallbacks used only when the platform_settings row is missing or unparseable.
    private static final double EMPLOYEE_RATE_PCT_DEFAULT = 8.0;   // 8%
    private static final double EMPLOYER_RATE_PCT_DEFAULT = 10.0;  // 10%

    /**
     * Pension is calculated on pensionable earnings = Basic + Housing + Transport
     * @param basicSalary in kobo
     * @param housingAllowance in kobo
     * @param transportAllowance in kobo
     * @return PensionResult containing employee and employer contributions
     */
    public PensionResult calculate(Long basicSalary, Long housingAllowance, Long transportAllowance) {
        Long pensionableEarnings = basicSalary + housingAllowance + transportAllowance;

        double employeeRate = platformSettingsService.getDoubleOrDefault(
                "pension_employee_pct", EMPLOYEE_RATE_PCT_DEFAULT) / 100.0;
        double employerRate = platformSettingsService.getDoubleOrDefault(
                "pension_employer_pct", EMPLOYER_RATE_PCT_DEFAULT) / 100.0;

        Long employeeContribution = Math.round(pensionableEarnings * employeeRate);
        Long employerContribution = Math.round(pensionableEarnings * employerRate);

        log.debug("Pension: Pensionable={}, Employee={}, Employer={}",
                pensionableEarnings, employeeContribution, employerContribution);

        return PensionResult.builder()
                .pensionableEarnings(pensionableEarnings)
                .employeeContribution(employeeContribution)
                .employerContribution(employerContribution)
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class PensionResult {
        private Long pensionableEarnings;
        private Long employeeContribution;
        private Long employerContribution;
    }
}