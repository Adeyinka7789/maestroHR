package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the Compliance &amp; Expiry dashboard
 * ({@code /htmx/compliance}), rendered as {@code compliance :: content}. Assembled by
 * {@link ComplianceDashboardService} from tenant-scoped reads — probation reviews that are
 * due (or overdue) and employee documents nearing (or past) expiry. Every field is
 * pre-formatted for direct display; the badge {@code *Kind} fields map to the same
 * success/warn/error/neutral colour buckets used across the detail pages.
 */
public record ComplianceDashboardView(
        // Probation reviews due (unconfirmed, ACTIVE, within 30 days or already overdue)
        int probationOverdueCount,
        int probationDueSoonCount,    // due within 7 days (inclusive of today)
        int probationDueLaterCount,   // due in 8..30 days
        List<ProbationRow> probationRows,

        // Fixed-term contracts ending (contractEndDate within 90 days or already lapsed)
        int contractsLapsedCount,
        int contractsEnding30Count,
        int contractsEnding90Count,
        List<ContractRow> contractRows,

        // Document & contract expiries (gated on the DOCUMENT_VAULT feature)
        boolean documentsAvailable,
        int docsExpiredCount,
        int docsExpiring30Count,
        int docsExpiring90Count,
        List<DocumentRow> documentRows
) {

    /** One employee whose probation window is closing, with a one-click confirm affordance. */
    public record ProbationRow(
            UUID employeeId,
            String fullName,
            String jobTitle,
            String probationEndFormatted,
            long daysRemaining,        // negative when overdue
            String bucketLabel,        // e.g. "Overdue by 3d" / "Due today" / "Due in 12d"
            String bucketKind          // error / warn / neutral
    ) {}

    /** One employee whose fixed-term contract is ending or has lapsed. */
    public record ContractRow(
            UUID employeeId,
            String fullName,
            String jobTitle,
            String contractEndFormatted,
            long daysRemaining,        // negative when the contract has already lapsed
            String bucketLabel,        // e.g. "Lapsed 4d ago" / "Ends today" / "Ends in 21d"
            String bucketKind          // error / warn / neutral
    ) {}

    /** One document nearing or past expiry, linking back to its owning employee. */
    public record DocumentRow(
            UUID employeeId,
            String employeeName,
            String documentType,       // humanized, e.g. "Work Permit"
            String fileName,
            String expiryFormatted,
            long daysRemaining,        // negative when already expired
            String bucketLabel,        // e.g. "Expired 5d ago" / "Expires in 20d"
            String bucketKind          // error / warn / neutral
    ) {}

    public boolean hasProbation() {
        return !probationRows.isEmpty();
    }

    public boolean hasContracts() {
        return !contractRows.isEmpty();
    }

    public int contractsTotal() {
        return contractRows.size();
    }

    public boolean hasDocuments() {
        return !documentRows.isEmpty();
    }

    public int probationTotal() {
        return probationRows.size();
    }

    public int documentsTotal() {
        return documentRows.size();
    }
}
