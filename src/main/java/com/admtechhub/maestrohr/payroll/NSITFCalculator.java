package com.admtechhub.maestrohr.payroll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NSITFCalculator {

    private static final double NSITF_RATE = 0.01;  // 1% of gross (employer only)

    /**
     * Nigeria Social Insurance Trust Fund: 1% of gross salary (Employer only)
     * @param grossSalary in kobo
     * @return NSITF contribution in kobo (not deducted from employee)
     */
    public Long calculateEmployerContribution(Long grossSalary) {
        Long nsitf = Math.round(grossSalary * NSITF_RATE);
        log.debug("NSITF Employer: Gross={}, Contribution={}", grossSalary, nsitf);
        return nsitf;
    }
}