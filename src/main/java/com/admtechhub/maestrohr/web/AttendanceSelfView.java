package com.admtechhub.maestrohr.web;

/**
 * Server-rendered view model for the employee self check-in/out card
 * ({@code templates/attendance-self.html}, rendered by {@link AttendanceSelfController}).
 * Built per request from the authenticated employee's own attendance record for the
 * current day — single-employee, own-record, the lower-stakes write of the attendance
 * module (Step D). Mirrors {@link AttendanceListView} / {@link LeaveListView}.
 *
 * The three button-state flags collapse the day into one of three states:
 *   - not checked in  → canCheckIn=true,  canCheckOut=false (clock-in is null);
 *   - checked in       → canCheckIn=false, canCheckOut=true  (clock-in set, clock-out null);
 *   - checked out      → canCheckIn=false, canCheckOut=false (both set).
 *
 * The error banner is NOT a field here — like the leave page, a failed action adds a
 * separate {@code error} model attribute alongside a freshly-rebuilt view (see
 * {@link AttendanceSelfController#handleActionFailure}).
 */
public record AttendanceSelfView(
        String dateFormatted,      // today humanized, e.g. "Monday, 15 June 2026"
        String employeeName,       // the authenticated employee's full name
        String statusLabel,        // "Not checked in yet" | "Checked in" | "Checked out"
        String statusKind,         // success | neutral -> badge colour
        String clockInFormatted,   // e.g. "09:05", or "—" when not clocked in
        String clockOutFormatted,  // e.g. "17:30", or "—" when not clocked out
        String hoursFormatted,     // e.g. "8.25", or "—" when not computed
        String notes,              // notes already recorded today, or null
        boolean canCheckIn,
        boolean canCheckOut
) {}
