package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned attendance "Today" fragment
 * (the HR-admin / manager daily roster). Assembled once on the server from a single
 * date-scoped query (no client-side JSON round-trips, no loading flash). Mirrors
 * {@link LeaveListView} / {@link DepartmentListView} / {@link EmployeeListView}.
 *
 * SCOPE (Step A): read-only list for a single day with a date picker (defaulting to
 * today), a status filter (All / Present / Late / Absent / Half Day), and free-text
 * employee search. The Monthly Calendar (Step B) and the write workflows — Mark
 * Attendance and Self check-in/out — are deferred to later, separately-reviewed steps
 * because the write paths feed payroll deductions.
 *
 * The filter-chip counts come from a date-scoped GROUP BY (see
 * {@code AttendanceRepository#countByStatusForDate}) so they reflect the full day's
 * roster, not the currently filtered view. ON_LEAVE has no chip of its own but is
 * still counted in {@link StatusChip} "All" and rendered (with a neutral badge) when
 * the All filter is active.
 */
public record AttendanceListView(
        List<Row> rows,
        long totalElements,        // rows in the current (filtered) view
        String date,               // active day in ISO form (yyyy-MM-dd), drives the date picker value
        String dateFormatted,      // active day humanized, e.g. "Monday, 15 June 2026"
        String search,             // active search term, or null
        String status,             // active status filter raw name (e.g. "PRESENT"), or null for All
        List<StatusChip> statusChips
) {

    /** A single rendered attendance row for the selected day. */
    public record Row(
            UUID id,
            String employeeName,
            String employeeInitials,
            String clockInFormatted,   // e.g. "09:05", or "—" when not clocked in
            String clockOutFormatted,  // e.g. "17:30", or "—" when not clocked out
            String hoursFormatted,     // e.g. "8.25", or "—" when not computed
            String statusName,         // raw enum name, e.g. "PRESENT"
            String statusLabel,        // humanized, e.g. "Present"
            String statusKind          // success | warn | error | neutral -> badge colour
    ) {}

    /** A status filter chip: value (""=All), label, the day's count, and whether it is active. */
    public record StatusChip(
            String value,
            String label,
            long count,
            boolean selected
    ) {}
}
