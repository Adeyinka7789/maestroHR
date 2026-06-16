package com.admtechhub.maestrohr.web;

import java.util.List;

/**
 * Server-rendered view model for the super-admin platform dashboard fragment
 * (templates/admin-dashboard.html). Mirrors the {@link DashboardView} pilot: every value is
 * computed once on the server and rendered into the HTML, so the page has no client-side
 * fetches and no loading flash.
 *
 * <p>All figures are cross-tenant aggregates sourced through the privileged datasource
 * (see {@code AdminStatsQueries}); the MRR is derived from the {@code pricing_config}
 * single source of truth in {@link SuperAdminDashboardService}.
 */
public record SuperAdminDashboardView(
        long totalTenants,
        long activeSubscriptions,
        String mrrFormatted,        // e.g. "₦1.25M" — compact NGN
        int systemHealthPercent,    // placeholder 100 until a real health probe exists
        List<TenantRow> recentTenants,
        List<LogRow> recentLogs
) {

    /** A row of the "Recent Tenants" table. */
    public record TenantRow(
            String initials,          // derived from the company name, e.g. "AL"
            String companyName,
            String rcNumber,          // "—" when absent
            String plan,              // raw plan name, e.g. "ENTERPRISE"
            String dateJoined,        // e.g. "Oct 12, 2023"
            boolean active,
            String statusLabel,       // "ACTIVE" | "INACTIVE"
            long userCount
    ) {}

    /** A row of the system-logs feed. */
    public record LogRow(
            String icon,              // Material Symbols name, e.g. "info"
            String iconKind,          // success | warn | error -> colour
            String title,             // humanized action
            String subtitle,          // actor + request context
            String whenLabel          // relative time, e.g. "2 mins ago"
    ) {}
}
