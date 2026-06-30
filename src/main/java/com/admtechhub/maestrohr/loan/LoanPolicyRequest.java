package com.admtechhub.maestrohr.loan;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanPolicyRequest {
    private String name;
    private String description;
    private Long minAmountKobo;
    private Long maxAmountKobo;
    private BigDecimal maxMultiplierOfGross;
    private BigDecimal maxDeductionPct;
    private BigDecimal netFloorPct;
    private Integer maxTenorMonths;
    private BigDecimal interestRatePct;
    private Integer coolingPeriodDays;
    private Integer maxLoansPerYear;
    private Long approvalThresholdKobo;
    private Integer eligibleAfterMonths;
    private Boolean probationEligible;
    private Boolean isActive;
}
