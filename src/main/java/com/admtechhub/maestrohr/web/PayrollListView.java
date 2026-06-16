package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned payroll-runs list fragment
 * (the HR-admin / finance run history + approval queue). Assembled once on the server
 * from tenant-scoped queries (no client-side JSON round-trips, no loading flash).
 * Mirrors {@link LeaveListView} / {@link AttendanceListView} / {@link EmployeeListView}.
 *
 * SCOPE (Step A): read-only list of payroll runs with a status filter
 * (All / Draft / Pending Approval / Approved / Disbursing / Completed / Rejected,
 * defaulting to All) and free-text search over the period / initiator. The run detail
 * page (Step B) and the write workflows — initiate, compute, submit, approve, reject,
 * disburse (Steps C/D) — are deferred to later, separately-reviewed steps because the
 * write paths move money.
 *
 * The filter-chip counts come from a tenant-wide GROUP BY (see
 * {@code PayrollRunRepository#countByStatusForTenant}) so they reflect the full data
 * set, not the currently filtered view. Monetary amounts are pre-formatted as Naira
 * strings (kobo / 100) so the template stays logic-free.
 */
public record PayrollListView(
        List<Row> rows,
        long totalElements,        // rows in the current (filtered) view
        String search,             // active search term, or null
        String status,             // active status filter raw name (e.g. "APPROVED"), or null for All
        List<StatusChip> statusChips
) {

    /** A single rendered payroll-run row. */
    public record Row(
            UUID id,
            String period,                // ISO-ish period key, e.g. "2026-06"
            String periodLabel,           // humanized, e.g. "June 2026"
            int entryCount,               // employees on the run (0 for an uncomputed draft)
            String netTotalFormatted,     // e.g. "₦1,234,567.00"
            String initiatedByEmail,      // who created the run
            String statusName,            // raw enum name, e.g. "PENDING_APPROVAL"
            String statusLabel,           // humanized, e.g. "Pending Approval"
            String statusKind,            // success | warn | error | neutral -> badge colour
            String createdFormatted       // createdAt as date, or "—"
    ) {}

    /** A status filter chip: value (""=All), label, the tenant-wide count, and whether it is active. */
    public record StatusChip(
            String value,
            String label,
            long count,
            boolean selected
    ) {}
}
