package com.admtechhub.maestrohr.admin.dto;

import lombok.Data;

import java.util.UUID;

/**
 * Admin create/update payload for a {@link com.admtechhub.maestrohr.tenant.Discount}.
 *
 * <p>Values are in human units — {@code percent} is 0–100 and {@code amountNaira} is naira;
 * the controller converts to the stored units (basis points / kobo). All targeting and window
 * fields are optional: a blank/null value means "applies to all" on that dimension.
 */
@Data
public class DiscountRequest {

    private String label;

    /** PERCENTAGE or FIXED. */
    private String discountType;

    /** Percentage off (0–100) when {@code discountType == PERCENTAGE}. */
    private Double percent;

    /** Naira off when {@code discountType == FIXED}. */
    private Double amountNaira;

    /** Target customer; null = all customers. */
    private UUID tenantId;

    /** Target plan (e.g. PROFESSIONAL); null/blank = all plans. */
    private String planName;

    /** Target period (MONTHLY/QUARTERLY/ANNUALLY); null/blank = all periods. */
    private String period;

    /** Validity window (inclusive), ISO {@code yyyy-MM-dd}; null/blank = unbounded. */
    private String startDate;
    private String endDate;

    private Boolean isActive;
}
