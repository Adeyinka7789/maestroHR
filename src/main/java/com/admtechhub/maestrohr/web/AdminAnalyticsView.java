package com.admtechhub.maestrohr.web;

import java.util.List;

/**
 * Server-rendered view model for the super-admin analytics console (templates/admin-analytics.html).
 *
 * <p>Assembled by {@link AdminAnalyticsService} from cross-tenant privileged reads. The chart series
 * are pre-split into parallel {@code labels} / {@code data} lists so the template can hand them
 * straight to Chart.js via Thymeleaf JS inlining ({@code th:inline="javascript"}), which serialises
 * each list to a JS array — no client-side parsing needed. Kobo totals sit beside their formatted
 * strings so the KPI cards get display-ready text while any API consumer keeps the raw numbers.
 */
public record AdminAnalyticsView(
        // KPI cards
        long totalTenants,
        long totalRevenueKobo,
        String totalRevenueFormatted,   // compact NGN, e.g. "₦4.2M"
        long avgMrrPerTenantKobo,
        String avgMrrPerTenantFormatted, // full NGN, e.g. "₦62,500"
        String fastestGrowingMonth,      // e.g. "Mar 2026", or "—" when flat

        // Tenant growth (line) — cumulative registrations, last 12 months
        List<String> tenantGrowthLabels,
        List<Long> tenantGrowthData,

        // MRR trend (bar) — cumulative MRR in NAIRA (kobo/100), last 12 months
        List<String> mrrLabels,
        List<Long> mrrData,

        // Plan distribution (doughnut) — current snapshot
        List<String> planLabels,
        List<Long> planData,

        // Subscription status (doughnut/pie) — current snapshot
        List<String> statusLabels,
        List<Long> statusData
) {
}
