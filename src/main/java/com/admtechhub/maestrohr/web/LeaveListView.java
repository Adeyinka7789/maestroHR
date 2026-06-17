package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned leave list fragment
 * (the HR-admin / manager approval-queue + history table). Assembled once on the
 * server from a single tenant-scoped query (no client-side JSON round-trips, no
 * loading flash). Mirrors {@link DepartmentListView} / {@link EmployeeListView}.
 *
 * SCOPE (Step A): read-only list with a status filter (All / Pending / Approved /
 * Rejected, defaulting to Pending) and free-text search. Approve/reject writes and
 * the admin "apply for leave" form are deferred to later steps.
 *
 * The filter-chip counts come from a tenant-wide GROUP BY (see
 * {@code LeaveRequestRepository#countByStatusForTenant}) so they reflect the full
 * data set, not the currently filtered view. CANCELLED requests have no chip of
 * their own but are still counted in {@link StatusChip} "All" and rendered (with a
 * neutral badge) when the All filter is active.
 *
 * SCOPE (Step C): the "Apply for Leave" form is now wired. The form-backing fields
 * ({@link #employees}, {@link #leaveTypes}, {@link #currentEmployeeId},
 * {@link #isEmployeeRole}) are populated only on full {@code content} builds
 * ({@code LeaveListService#buildContent}); table-only swaps leave them empty/false
 * since the {@code table} fragment doesn't reference them. When {@code isEmployeeRole}
 * is true the employee picker is locked to {@code currentEmployeeId} (a self-service
 * employee may only apply for themselves — see the IDOR guard in the controller).
 */
public record LeaveListView(
        List<Row> rows,
        long totalElements,        // rows in the current (filtered) view
        String search,             // active search term, or null
        String status,             // active status filter raw name (e.g. "PENDING"), or null for All
        List<StatusChip> statusChips,
        List<EmployeeOption> employees,    // tenant roster for the Apply form's picker (content builds only)
        List<LeaveTypeOption> leaveTypes,  // leave types for the Apply form's picker (content builds only)
        UUID currentEmployeeId,            // the authenticated user's own employee id, or null (no profile)
        boolean isEmployeeRole             // true → self-service employee: lock the picker to currentEmployeeId
) {

    /** A single rendered leave-request row. */
    public record Row(
            UUID id,
            String employeeName,
            String employeeInitials,
            String leaveTypeName,
            String startFormatted,     // e.g. "02 Jun 2025"
            String endFormatted,       // e.g. "06 Jun 2025"
            int daysRequested,
            String reason,             // may be blank
            String statusName,         // raw enum name, e.g. "PENDING"
            String statusLabel,        // humanized, e.g. "Pending"
            String statusKind,         // success | warn | error | neutral -> badge colour
            String submittedFormatted  // createdAt as date, or "—"
    ) {}

    /** A status filter chip: value (""=All), label, the tenant-wide count, and whether it is active. */
    public record StatusChip(
            String value,
            String label,
            long count,
            boolean selected
    ) {}

    /** An option in the Apply form's employee picker: id + display label (name + number). */
    public record EmployeeOption(
            UUID id,
            String label
    ) {}

    /** An option in the Apply form's leave-type picker: id + display name. */
    public record LeaveTypeOption(
            UUID id,
            String name
    ) {}
}
