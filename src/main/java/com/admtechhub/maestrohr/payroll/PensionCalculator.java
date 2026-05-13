package com.admtechhub.maestrohr.payroll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PensionCalculator {

    private static final double EMPLOYEE_RATE = 0.08;  // 8%
    private static final double EMPLOYER_RATE = 0.10;  // 10%

    /**
     * Pension is calculated on pensionable earnings = Basic + Housing + Transport
     * @param basicSalary in kobo
     * @param housingAllowance in kobo
     * @param transportAllowance in kobo
     * @return PensionResult containing employee and employer contributions
     */
    public PensionResult calculate(Long basicSalary, Long housingAllowance, Long transportAllowance) {
        Long pensionableEarnings = basicSalary + housingAllowance + transportAllowance;

        Long employeeContribution = Math.round(pensionableEarnings * EMPLOYEE_RATE);
        Long employerContribution = Math.round(pensionableEarnings * EMPLOYER_RATE);

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