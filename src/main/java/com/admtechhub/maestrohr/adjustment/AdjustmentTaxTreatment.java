package com.admtechhub.maestrohr.adjustment;

/**
 * How an adjustment interacts with PAYE. Valid values depend on the {@link AdjustmentDirection}
 * (enforced by the DB check constraint in V61 and by {@link AdjustmentType#validate()}):
 * <ul>
 *   <li>EARNING → {@link #TAXABLE} (added to gross and taxed at the marginal band) or
 *       {@link #NON_TAXABLE} (added to gross/net, not taxed — e.g. a reimbursement).</li>
 *   <li>DEDUCTION → {@link #PRE_TAX} (reduces taxable income as relief AND net — e.g. voluntary
 *       pension) or {@link #POST_TAX} (reduces net only, floor-protected — e.g. a fine).</li>
 * </ul>
 */
public enum AdjustmentTaxTreatment {
    TAXABLE,
    NON_TAXABLE,
    PRE_TAX,
    POST_TAX
}
