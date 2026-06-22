package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the super-admin subscribers list fragment
 * (templates/subscribers.html). Mirrors the {@link EmployeeListView} pattern: the table and
 * its company/plan search + status/plan filters are assembled once on the server, so the page
 * has no client-side JSON fetch and no loading flash.
 *
 * <p>The four stat figures are global, cross-tenant aggregates (sourced through the privileged
 * datasource in {@link SubscribersListService}); they reflect the whole portfolio and are
 * intentionally unaffected by the search/filter, which only narrows {@link #rows}.
 */
public record SubscribersListView(
        long totalSubscribers,
        long activeCount,
        long trialCount,
        long totalUsers,
        String search,          // echoed search term, or "" — repopulates the search box
        String statusFilter,    // "", "Active", or "Inactive"
        String planFilter,      // "", or a SubscriptionPlan name
        List<Row> rows
) {

    /** A single rendered subscriber (tenant) row. */
    public record Row(
            UUID id,
            String companyName,
            String industry,
            String companySize,
            String plan,                // raw plan name, e.g. "ENTERPRISE"
            boolean active,
            String statusLabel,         // "Active" | "Inactive"
            String expiresFormatted,    // e.g. "31 Dec 2025" or "—"
            String rcNumber,            // "—" when absent
            long userCount
    ) {}
}
