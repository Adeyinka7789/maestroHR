package com.admtechhub.maestrohr.payroll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NHFCalculator {

    private static final double NHF_RATE = 0.025;  // 2.5% of basic salary

    /**
     * National Housing Fund: 2.5% of basic salary
     * @param basicSalary in kobo
     * @return NHF deduction in kobo
     */
    public Long calculate(Long basicSalary) {
        Long nhf = Math.round(basicSalary * NHF_RATE);
        log.debug("NHF: Basic={}, NHF Deduction={}", basicSalary, nhf);
        return nhf;
    }
}