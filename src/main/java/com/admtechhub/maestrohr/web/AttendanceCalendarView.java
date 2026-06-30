package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the attendance Monthly Calendar fragment (Step B) —
 * the HR-admin / manager per-employee month grid. Companion to the Step-A daily roster
 * {@link AttendanceListView}; kept as a separate model (and backed by a separate
 * {@code AttendanceCalendarService} / {@code AttendanceCalendarController}) because the
 * month grid is structurally unlike the daily table and the Step-A list classes are
 * documented as roster-scoped.
 *
 * Assembled once on the server from a single month-and-employee-scoped query (no
 * client-side JSON round-trips, no loading flash). The page surfaces a tab toggle
 * (Today / Calendar) in {@code attendance :: content}; the Calendar tab swaps this
 * fragment in via {@code GET /htmx/attendance/calendar}.
 *
 * SCOPE (Step B): read-only. The employee picker, prev/next month navigation, and the
 * search-free month grid are all GETs; no write actions. Self check-in/out and Mark
 * Attendance (Steps C/D) remain deferred because the write paths feed payroll deductions.
 *
 * Present-rate semantics (per product decision):
 *   - denominator = days WITH a record that month ({@link #recordedDays}), NOT calendar
 *     days, so a sparsely-recorded or mid-month view is not reported as misleadingly low;
 *   - numerator   = PRESENT records only ({@link #presentDays}) — LATE / HALF_DAY stay
 *     distinct and are surfaced through their own day symbols, not folded into the rate;
 *   - {@link #presentRate} is null when {@link #recordedDays} is 0 (no records → no rate).
 */
public record AttendanceCalendarView(
        // ── employee picker ────────────────────────────────────────────────────────
        List<EmployeeOption> employees,
        UUID selectedEmployeeId,
        String selectedEmployeeName,
        boolean employeeSelected,   // false → show the "select an employee" prompt, no grid

        // ── active period ──────────────────────────────────────────────────────────
        int year,
        int month,
        String monthLabel,          // e.g. "June 2026"
        int prevYear,
        int prevMonth,
        int nextYear,
        int nextMonth,

        // ── month grid ─────────────────────────────────────────────────────────────
        // Leading blank cells (for the first-of-month weekday offset, Sunday-start) plus
        // one cell per day of the month, in order. Empty when no employee is selected.
        List<DayCell> cells,

        // ── summary ────────────────────────────────────────────────────────────────
        long presentDays,           // PRESENT records this month
        long recordedDays,          // days with ANY record this month (rate denominator)
        Integer presentRate         // round(presentDays / recordedDays * 100), or null when recordedDays == 0
) {

    /** One option in the employee picker — the employee id, a display label (name + number), and their department name. */
    public record EmployeeOption(
            UUID id,
            String label,
            String department
    ) {}

    /**
     * A single month-grid cell. Either a leading blank ({@code blank == true}, all other
     * fields empty/false) used to align day 1 under its weekday, or a real day. A real day
     * with no attendance record has a null {@code statusName} and an empty {@code symbol}.
     */
    public record DayCell(
            Integer day,            // day-of-month, or null for a leading blank
            boolean blank,
            String statusName,      // raw enum name (e.g. "PRESENT"), or null when no record
            String statusLabel,     // humanized (e.g. "Half Day"), or null when no record
            String statusKind,      // success | warn | error | neutral -> cell colour; null when no record
            String symbol,          // ✓ ✗ ⏰ ½ L, or "" when no record / blank
            boolean today,
            boolean weekend
    ) {}
}
