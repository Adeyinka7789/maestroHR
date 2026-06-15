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
 */
public record LeaveListView(
        List<Row> rows,
        long totalElements,        // rows in the current (filtered) view
        String search,             // active search term, or null
        String status,             // active status filter raw name (e.g. "PENDING"), or null for All
        List<StatusChip> statusChips
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
}
