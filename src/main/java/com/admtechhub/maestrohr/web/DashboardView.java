package com.admtechhub.maestrohr.web;

import java.util.List;

/**
 * Server-rendered view model for the redesigned dashboard fragment
 * (templates/dashboard.html). Everything the page shows is computed once on the
 * server and rendered into the HTML — no client-side JSON round-trips, no loading flash.
 */
public record DashboardView(
        String periodLabel,        // e.g. "June 2025"
        long activeHeadcount,
        long newHiresThisMonth,
        long onLeaveCount,
        long pendingLeaveCount,
        PayrollSummary payroll,
        List<ActivityItem> recentActivity,
        List<CelebrationItem> birthdays,
        List<CelebrationItem> anniversaries
) {

    /** The "current payroll" metric card. */
    public record PayrollSummary(
            boolean present,
            String monthLabel,         // e.g. "June 2025"
            String amountFormatted,    // e.g. "₦42,500,000.00"
            String status,             // raw enum name, e.g. "PENDING_APPROVAL"
            String statusLabel,        // humanized, e.g. "Pending Approval"
            String statusKind          // success | warn | error | neutral -> badge colour
    ) {}

    /** A row in the "Recent Activity" table (sourced from the audit trail). */
    public record ActivityItem(
            String icon,               // Material Symbols name
            String iconKind,           // success | warn | error | neutral -> tile colour
            String event,              // humanized action
            String actor,              // actor email / name
            String dateLabel,          // e.g. "Jun 14, 2025"
            String statusText,         // e.g. "COMPLETED"
            String statusKind          // success | warn | error | neutral
    ) {}

    /** A birthday or work-anniversary entry in the celebrations widgets. */
    public record CelebrationItem(
            String initials,
            String name,
            String subtitle,           // department, or "Role • date"
            String whenLabel,          // e.g. "Today", "Tomorrow", "Jun 16"
            String badge               // e.g. "3y" for anniversaries, empty for birthdays
    ) {}
}
