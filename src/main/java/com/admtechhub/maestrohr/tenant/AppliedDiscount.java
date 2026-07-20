package com.admtechhub.maestrohr.tenant;

import java.util.UUID;

/**
 * The result of resolving the best discount for a purchase.
 *
 * @param discountId    the winning discount's id, or {@code null} if none applied
 * @param label         the winning discount's label, or {@code null} if none applied
 * @param baseKobo      the un-discounted base price
 * @param discountKobo  amount taken off the base price (0 when none)
 * @param netKobo       the amount to actually charge ({@code baseKobo - discountKobo}, floored at 0)
 */
public record AppliedDiscount(UUID discountId, String label, long baseKobo, long discountKobo, long netKobo) {

    /** No discount applied: net equals base. */
    public static AppliedDiscount none(long baseKobo) {
        return new AppliedDiscount(null, null, baseKobo, 0L, baseKobo);
    }

    public boolean hasDiscount() {
        return discountKobo > 0;
    }
}
