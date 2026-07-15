package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the attendance <b>Analytics</b> fragment — the third tab on
 * the attendance page (alongside Today's roster and the per-employee Calendar). Where
 * {@link AttendanceCalendarView} answers "how was ONE employee this month", this answers "how
 * was the WHOLE team over a period": company-wide summary cards, a per-employee summary table,
 * a per-department breakdown, and an attendance trend.
 *
 * <h3>Period model</h3>
 * The tab tracks four period types, chosen with a Day / Week / Month / Custom toggle:
 * <ul>
 *   <li><b>day</b> — a single date; the trend is hidden (a one-bar trend says nothing).</li>
 *   <li><b>week</b> — the Monday–Sunday week containing the anchor date; daily trend bars.</li>
 *   <li><b>month</b> — the calendar month containing the anchor; daily trend bars.</li>
 *   <li><b>custom</b> — an arbitrary from/to range picked with two date inputs; the same range
 *       also drives the Excel export. Long ranges switch the trend to weekly buckets.</li>
 * </ul>
 * Whatever the period, {@link #startDate} / {@link #endDate} are the resolved ISO window that
 * every card, table, trend bar, and the export link agree on. Preset periods navigate with
 * {@link #prevDate} / {@link #nextDate} (an anchor date inside the neighbouring period); custom
 * navigates by sliding the whole window ({@link #prevFrom}/{@link #prevTo} …).
 *
 * <h3>Attendance-rate semantics (one definition, used everywhere on this tab)</h3>
 * <pre>
 *   attended        = PRESENT + LATE + HALF_DAY
 *   working records = PRESENT + LATE + HALF_DAY + ABSENT
 *   rate            = round(attended / working records * 100), or null when working == 0
 * </pre>
 * ON_LEAVE is excluded from both numerator and denominator (authorized leave is neither
 * attendance nor absenteeism) but is still surfaced as its own count. This differs deliberately
 * from the Calendar's stricter present-only rate.
 */
public record AttendanceAnalyticsView(
        // ── active period ────────────────────────────────────────────────────────────
        String period,              // "day" | "week" | "month" | "custom"
        boolean custom,             // period == "custom" (template convenience)
        String startDate,           // resolved ISO window start (display, export ?from=, custom input)
        String endDate,             // resolved ISO window end   (display, export ?to=,   custom input)
        String rangeLabel,          // human label, e.g. "June 2026" / "9 Jun – 15 Jun 2026"

        // preset (day/week/month) nav — an anchor date inside the neighbouring period
        String prevDate,
        String nextDate,
        // custom nav — the slid window
        String prevFrom,
        String prevTo,
        String nextFrom,
        String nextTo,

        // ── department filter ────────────────────────────────────────────────────────
        List<DeptOption> departments,
        String selectedDepartmentId, // null → All departments

        // ── company-wide summary (the stat cards) ────────────────────────────────────
        Summary summary,

        // ── per-employee summary table (only employees with ≥1 record in range) ──────
        List<EmployeeRow> employeeRows,

        // ── per-department breakdown ─────────────────────────────────────────────────
        List<DepartmentRow> departmentRows,

        // ── attendance trend ─────────────────────────────────────────────────────────
        List<TrendPoint> trend,
        boolean showTrend,          // false for a single-day window (nothing to trend)
        boolean weeklyTrend,        // true when the trend is bucketed by week (long ranges)
        int trendMaxRate            // the tallest bar's rate; 0 when the range is empty
) {

    /** A department option for the filter dropdown. id "" = All. */
    public record DeptOption(String id, String name) {}

    /**
     * Company-wide totals for the range. {@code attendanceRate} follows the class-level
     * definition (null when there are no working records). {@code headcount} is the number of
     * distinct employees with any record in the range; {@code activeDays} is the number of
     * calendar days in the range that have at least one record.
     */
    public record Summary(
            long present,
            long late,
            long absent,
            long halfDay,
            long onLeave,
            long totalRecords,       // every status, incl. ON_LEAVE
            long workingRecords,     // present + late + halfDay + absent (rate denominator)
            Integer attendanceRate,  // % or null
            String rateKind,         // success | warn | error | neutral -> card accent
            int headcount,
            int activeDays,
            int daysInRange
    ) {}

    /** One row of the per-employee summary table. {@code rate}/{@code rateKind} per the class definition. */
    public record EmployeeRow(
            UUID employeeId,
            String name,
            String number,           // employee number, or "" when unset
            String department,       // department name, or "—"
            long present,
            long late,
            long absent,
            long halfDay,
            long onLeave,
            Integer rate,
            String rateKind          // success | warn | error | neutral
    ) {}

    /** One row of the per-department breakdown. Aggregates the employee rows by department. */
    public record DepartmentRow(
            String department,       // department name, or "Unassigned"
            int headcount,           // distinct employees with a record in range
            long present,
            long late,
            long absent,
            long halfDay,
            long onLeave,
            Integer rate,
            String rateKind
    ) {}

    /**
     * One bar of the trend strip — a single day (daily granularity) or a 7-day bucket (weekly,
     * for long ranges). {@code barHeightPct} is the point's {@code rate} clamped to [0,100]
     * (0 when {@code rate} is null / no records), used directly as the bar height. {@code label}
     * is the x-axis caption (day-of-month, or the bucket-start day for weekly). {@code tooltip}
     * is the pre-built native-title breakdown.
     */
    public record TrendPoint(
            String dateIso,          // yyyy-MM-dd (the day, or the bucket-start day)
            String label,            // x-axis caption
            long present,
            long late,
            long absent,
            long halfDay,
            long onLeave,
            Integer rate,            // % or null (no records in the point)
            int barHeightPct,        // 0..100
            boolean weekend,         // daily only; false for weekly buckets
            boolean today,           // the point covers today
            String tooltip
    ) {}
}
