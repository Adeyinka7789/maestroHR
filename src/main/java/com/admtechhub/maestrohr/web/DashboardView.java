package com.admtechhub.maestrohr.web;

import java.util.List;

/**
 * Server-rendered view model for the redesigned dashboard fragment
 * (templates/dashboard.html). Everything the page shows is computed once on the
 * server and rendered into the HTML — no client-side JSON round-trips, no loading flash.
 *
 * The bottom of the page is organised into two HR-meaningful sections instead of a
 * raw audit-log table:
 *   - "Action Required"        — pendingLeaveCount + pendingPayrollApproval
 *   - "This Month at a Glance" — attendanceToday, leaveDaysThisMonth, newHiresThisMonth,
 *                                 plus the birthdays/anniversaries celebrations.
 */
public record DashboardView(
        String periodLabel,        // e.g. "June 2025"
        long activeHeadcount,
        long newHiresThisMonth,
        long onLeaveCount,
        long pendingLeaveCount,
        long pendingPayrollApproval,
        PayrollSummary payroll,
        AttendanceToday attendanceToday,
        long leaveDaysThisMonth,
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

    /**
     * Today's attendance snapshot for the "This Month at a Glance" section.
     * {@code present} counts everyone who showed up (PRESENT + LATE + HALF_DAY);
     * {@code total} is the expected roster for the day (all marked records except
     * those ON_LEAVE). When no records exist for today {@code hasRecords} is false
     * and the template shows a graceful empty state.
     */
    public record AttendanceToday(
            boolean hasRecords,
            long present,
            long total,
            int ratePercent            // round(present * 100 / total), 0 when no records
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
