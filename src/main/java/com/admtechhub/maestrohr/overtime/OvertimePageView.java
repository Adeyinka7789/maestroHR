package com.admtechhub.maestrohr.overtime;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the Overtime page ({@code /htmx/overtime}), rendered as
 * {@code overtime :: content}. Money is pre-formatted as naira; the {@code *Kind} fields map to the
 * shared success/warn/error/neutral badge buckets.
 */
public record OvertimePageView(
        int month,
        int year,
        String periodLabel,          // e.g. "July 2026"
        PolicyView policy,
        List<EntryRow> entries,
        int draftCount,
        int approvedCount,
        String totalDraftFormatted,     // sum of DRAFT amounts, naira
        String totalApprovedFormatted   // sum of APPROVED amounts, naira
) {

    /** The tenant's current overtime rate card, for the inline settings form. */
    public record PolicyView(
            String standardDailyHours,
            int standardMonthlyHours,
            String weekdayMultiplier,
            String weekendMultiplier
    ) {}

    /** One employee's computed overtime for the period. */
    public record EntryRow(
            UUID id,
            UUID employeeId,
            String employeeName,
            String jobTitle,
            String weekdayHours,        // e.g. "6.50"
            String weekendHours,
            String holidayHours,
            String hourlyRateFormatted, // naira
            String amountFormatted,     // naira
            String statusLabel,         // "Draft" / "Approved" / "Rejected"
            String statusKind,          // warn / success / error
            boolean canDecide           // status == DRAFT
    ) {}

    public boolean hasEntries() {
        return !entries.isEmpty();
    }
}
