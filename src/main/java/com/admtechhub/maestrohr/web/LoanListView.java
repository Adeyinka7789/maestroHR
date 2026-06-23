package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the HR/Finance loan approval + tracking page
 * ({@code /htmx/loans}), rendered as {@code loans :: content}. Employees apply on their own
 * "My Loans" page; this view lists every loan in the tenant for HR/Finance to approve / reject
 * and to manage active ones. Mirrors {@link LeaveListView}.
 */
public record LoanListView(
        List<Row> rows,
        long pendingCount,                   // PENDING requests awaiting a decision
        long activeCount,
        String totalOutstandingFormatted     // sum of remaining balances across ACTIVE loans
) {

    /** A single rendered loan row. */
    public record Row(
            UUID id,
            String employeeName,
            String employeeInitials,
            String employeeNumber,
            String loanAmountFormatted,
            String monthlyInstallmentFormatted,
            String remainingFormatted,
            String progress,            // e.g. "2 / 6 months"
            String startDateFormatted,
            String description,         // may be blank
            String statusName,          // raw enum name, e.g. "PENDING"
            String statusLabel,         // humanized, e.g. "Pending"
            String statusKind,          // success | warn | error | neutral -> badge colour
            boolean canApprove,         // PENDING
            boolean canReject,          // PENDING
            boolean canPause,           // ACTIVE
            boolean canResume,          // PAUSED
            boolean canCancel           // ACTIVE or PAUSED
    ) {}
}
