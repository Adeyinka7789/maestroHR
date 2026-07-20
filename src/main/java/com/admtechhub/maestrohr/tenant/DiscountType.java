package com.admtechhub.maestrohr.tenant;

/**
 * How a {@link Discount}'s value is expressed.
 *
 * <ul>
 *   <li>{@link #PERCENTAGE} — a percentage off the base price, stored as basis points
 *       ({@code percentBps}); 2000 bps = 20%.</li>
 *   <li>{@link #FIXED} — a flat amount off the base price, stored in kobo ({@code amountKobo}).</li>
 * </ul>
 */
public enum DiscountType {
    PERCENTAGE,
    FIXED
}
