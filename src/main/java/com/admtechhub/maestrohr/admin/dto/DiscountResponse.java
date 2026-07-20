package com.admtechhub.maestrohr.admin.dto;

import com.admtechhub.maestrohr.tenant.Discount;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-facing view of a {@link Discount}, in human units (percent / naira) and with the target
 * customer's company name resolved for display.
 */
public record DiscountResponse(
        UUID id,
        String label,
        String discountType,
        Double percent,
        Double amountNaira,
        UUID tenantId,
        String companyName,
        String planName,
        String period,
        String startDate,
        String endDate,
        boolean isActive,
        String createdAt) {

    public static DiscountResponse from(Discount d, Map<UUID, String> tenantNames) {
        String company = d.getTenantId() == null
                ? "All customers"
                : tenantNames.getOrDefault(d.getTenantId(), "(unknown customer)");
        return new DiscountResponse(
                d.getId(),
                d.getLabel(),
                d.getDiscountType() != null ? d.getDiscountType().name() : null,
                d.getPercentBps() != null ? d.getPercentBps() / 100.0 : null,
                d.getAmountKobo() != null ? d.getAmountKobo() / 100.0 : null,
                d.getTenantId(),
                company,
                d.getPlanName(),
                d.getPeriod(),
                d.getStartsAt() != null ? d.getStartsAt().format(DateTimeFormatter.ISO_LOCAL_DATE) : null,
                d.getEndsAt() != null ? d.getEndsAt().format(DateTimeFormatter.ISO_LOCAL_DATE) : null,
                Boolean.TRUE.equals(d.getIsActive()),
                d.getCreatedAt() != null ? d.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);
    }
}
