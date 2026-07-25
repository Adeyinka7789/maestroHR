package com.admtechhub.maestrohr.adjustment;

/**
 * One employee's adjustments for a period, aggregated into the four buckets the payroll engine
 * understands (all kobo, all non-negative). Produced by
 * {@link PayrollAdjustmentService#computeBucketsForPeriod} and passed into
 * {@code PayrollEngine.calculateEmployeePayroll}.
 *
 * @param taxableEarnings     EARNING + TAXABLE — added to gross and taxed at the marginal band
 * @param nonTaxableEarnings  EARNING + NON_TAXABLE — added to gross/net, not taxed
 * @param preTaxDeductions    DEDUCTION + PRE_TAX — reduces taxable income (relief) and net
 * @param postTaxDeductions   DEDUCTION + POST_TAX — reduces net only, floor-protected like loans
 */
public record AdjustmentBuckets(long taxableEarnings, long nonTaxableEarnings,
                                long preTaxDeductions, long postTaxDeductions) {

    private static final AdjustmentBuckets ZERO = new AdjustmentBuckets(0, 0, 0, 0);

    public static AdjustmentBuckets zero() {
        return ZERO;
    }

    /** Fold one adjustment line into the running totals, routing by its type. */
    public AdjustmentBuckets plus(AdjustmentDirection direction, AdjustmentTaxTreatment tax, long amount) {
        return switch (tax) {
            case TAXABLE -> new AdjustmentBuckets(taxableEarnings + amount, nonTaxableEarnings, preTaxDeductions, postTaxDeductions);
            case NON_TAXABLE -> new AdjustmentBuckets(taxableEarnings, nonTaxableEarnings + amount, preTaxDeductions, postTaxDeductions);
            case PRE_TAX -> new AdjustmentBuckets(taxableEarnings, nonTaxableEarnings, preTaxDeductions + amount, postTaxDeductions);
            case POST_TAX -> new AdjustmentBuckets(taxableEarnings, nonTaxableEarnings, preTaxDeductions, postTaxDeductions + amount);
        };
    }
}
