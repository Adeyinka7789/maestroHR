package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the attendance <b>Analytics</b> fragment — the third
 * tab on the attendance page (alongside Today's roster and the per-employee Calendar).
 * Where {@link AttendanceCalendarView} answers "how was ONE employee this month", this
 * answers "how was the WHOLE team over a period": company-wide summary cards, a
 * per-employee summary table, a per-department breakdown, and a day-by-day attendance
 * trend. Assembled once on the server from three grouped range queries (no client-side
 * fetches), companion to {@link AttendanceAnalyticsService} / {@code AttendanceAnalyticsController}.
 *
 * <h3>Attendance-rate semantics (one definition, used everywhere on this tab)</h3>
 * <pre>
 *   attended        = PRESENT + LATE + HALF_DAY      (all "showed up" states)
 *   working records = PRESENT + LATE + HALF_DAY + ABSENT
 *   rate            = round(attended / working records * 100), or null when working == 0
 * </pre>
 * ON_LEAVE is <em>excluded</em> from both numerator and denominator: authorized leave is
 * neither attendance nor absenteeism, so counting it would understate the rate for teams
 * with people legitimately off. It is still surfaced as its own column/count so nothing is
 * hidden. This differs deliberately from the Calendar's stricter present-only rate (which
 * keeps LATE/HALF_DAY as distinct day symbols); the two views answer different questions and
 * each documents its own denominator.
 *
 * SCOPE: read-only. The Excel export ({@code GET /htmx/attendance/export}) is a sibling
 * download, not part of this model — the export link just carries this view's {@link #startDate}
 * / {@link #endDate} / {@link #selectedDepartmentId}.
 */
public record AttendanceAnalyticsView(
        // ── active period (a calendar month, navigable prev/next like the Calendar tab) ──
        int year,
        int month,
        String monthLabel,          // e.g. "June 2026"
        int prevYear,
        int prevMonth,
        int nextYear,
        int nextMonth,
        String startDate,           // ISO first-of-month, drives the export link's ?from=
        String endDate,             // ISO end-of-month, drives the export link's ?to=

        // ── department filter ────────────────────────────────────────────────────────
        List<DeptOption> departments,
        String selectedDepartmentId, // null → All departments

        // ── company-wide summary (the stat cards) ────────────────────────────────────
        Summary summary,

        // ── per-employee summary table (only employees with ≥1 record in range) ──────
        List<EmployeeRow> employeeRows,

        // ── per-department breakdown ─────────────────────────────────────────────────
        List<DepartmentRow> departmentRows,

        // ── day-by-day attendance-rate trend (one point per calendar day in range) ───
        List<TrendPoint> trend,
        int trendMaxRate            // the tallest bar's rate (for scaling); 0 when the range is empty
) {

    /** A department option for the filter dropdown. id "" = All. */
    public record DeptOption(String id, String name) {}

    /**
     * Company-wide totals for the range. {@code attendanceRate} follows the class-level
     * definition (null when there are no working records). {@code headcount} is the number
     * of distinct employees with any record in the range; {@code activeDays} is the number
     * of calendar days in the range that have at least one record.
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
     * One day of the trend strip. {@code barHeightPct} is the day's {@code rate} clamped to
     * [0,100] (0 when {@code rate} is null / no records), used directly as the bar height so
     * the strip reads as "attendance rate per day". {@code weekend} lets the template mute
     * weekend bars. {@code tooltip} is the pre-built native-title breakdown.
     */
    public record TrendPoint(
            String dateIso,          // yyyy-MM-dd
            String dayLabel,         // day-of-month, e.g. "15"
            long present,
            long late,
            long absent,
            long halfDay,
            long onLeave,
            Integer rate,            // % or null (no records that day)
            int barHeightPct,        // 0..100
            boolean weekend,
            boolean today,
            String tooltip           // e.g. "15 Jun — 92% (P23 L1 A1 H0)"
    ) {}
}
