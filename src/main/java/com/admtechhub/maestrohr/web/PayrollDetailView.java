package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned payroll-run detail fragment
 * (the per-run statutory summary + per-employee breakdown). Assembled once on the
 * server from the run plus its entries (no client-side JSON round-trips). Mirrors the
 * {@code *ListView} records but represents a single aggregate rather than a table page.
 *
 * SCOPE (Step B): read-only detail. The {@code canSubmit / canApprove / canReject /
 * editable} flags are surfaced now so the template can render the Step C/D action
 * buttons in the correct enabled/hidden state, but the write handlers themselves
 * (submit / approve / reject / disburse) are deferred to later, separately-reviewed
 * steps because they move money. Approve is additionally gated to FINANCE_OFFICER /
 * SUPER_ADMIN at the controller layer (segregation of duties) — these flags only
 * reflect the run's status, not the viewer's role.
 *
 * Monetary amounts are pre-formatted as Naira strings (kobo / 100) so the template
 * stays logic-free.
 */
public record PayrollDetailView(
        UUID id,
        String period,                          // ISO-ish period key, e.g. "2026-06"
        String periodLabel,                     // humanized, e.g. "June 2026"
        String statusName,                      // raw enum name, e.g. "PENDING_APPROVAL"
        String statusLabel,                     // humanized, e.g. "Pending Approval"
        String statusKind,                      // success | warn | error | neutral -> badge colour

        // ── statutory / summary totals (pre-formatted Naira) ──
        String grossTotalFormatted,
        String netTotalFormatted,
        String payeTotalFormatted,
        String pensionEmployeeFormatted,
        String pensionEmployerFormatted,
        String nhfTotalFormatted,
        int entryCount,

        // ── people / audit ──
        String initiatedByEmail,
        String approvedByEmail,                 // null until approved
        String approvedAtFormatted,             // "—" until approved
        String rejectionReason,                 // null/blank unless rejected
        String reversedByEmail,                 // null unless reversed
        String reversedAtFormatted,             // null unless reversed
        String reversalReason,                  // null unless reversed

        // ── action availability (drives Step C/D buttons) ──
        // Raw status flags: whether the run's *state* permits each transition.
        boolean editable,
        boolean canSubmit,
        boolean canApprove,
        boolean canReject,

        // Effective button visibility: the status flag AND the viewer holds the role the
        // matching write endpoint requires (there is no sec:authorize dialect in these
        // templates, so the gate is resolved server-side here). A button is only rendered
        // when its show* flag is true, so the viewer never sees an action that would 403.
        boolean showCompute,
        boolean showSubmit,
        boolean showApprove,
        boolean showReject,

        // Step D — payment-file export and lifecycle completion.
        // showExport: run is APPROVED/DISBURSING/COMPLETED (payable figures are final) and the
        //   viewer holds an HR/finance role — the bank payment file may be downloaded.
        // showMarkPaid: run is APPROVED and the viewer is FINANCE_OFFICER/SUPER_ADMIN — they
        //   may attest the out-of-band bank upload completed (APPROVED → COMPLETED).
        boolean showExport,
        boolean showMarkPaid,

        // Reversal — SYSTEM_ADMIN/FINANCE_OFFICER only; run must be APPROVED/DISBURSING/COMPLETED.
        boolean showReverse,

        List<EntryRow> rows
) {

    /** A single employee's line within the run. */
    public record EntryRow(
            UUID id,
            String employeeName,
            String employeeNumber,
            String employeeInitials,
            String grossFormatted,
            String payeFormatted,
            String pensionEmployeeFormatted,
            String nhfFormatted,
            String unpaidLeaveDeductionFormatted,
            String attendanceDeductionFormatted,
            String adjustmentEarningsFormatted,  // bonuses + reimbursements (V61 one-off earnings)
            String adjustmentDeductionsFormatted, // voluntary pension + fines/advances (V61 one-off deductions)
            int lateDaysInPeriod,                // informational — not auto-deducted
            String netFormatted
    ) {}
}
