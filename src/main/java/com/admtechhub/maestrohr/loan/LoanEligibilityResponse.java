package com.admtechhub.maestrohr.loan;

import java.time.LocalDate;

public record LoanEligibilityResponse(
        boolean eligible,
        String reason,
        long maxAmountNaira,
        int maxTenorMonths,
        double interestRatePct,
        long activeLoans,
        int maxLoansPerYear,
        long loansThisYear,
        int coolingPeriodDays,
        LocalDate cooldownEndsDate,
        String policyName
) {
    static LoanEligibilityResponse noPolicyDefaults() {
        return new LoanEligibilityResponse(
                true, null, 10_000_000L, 24, 0.0, 0, 99, 0, 0, null, null);
    }
}
