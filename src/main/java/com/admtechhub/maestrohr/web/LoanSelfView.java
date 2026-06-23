package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the employee self-service "My Loans" page
 * ({@code /htmx/loans/me}), rendered as {@code loans-me :: content}. Shows the authenticated
 * employee's own loan requests and loans (read-only) plus the apply form. Mirrors
 * {@link PayslipSelfView}.
 */
public record LoanSelfView(
        String employeeName,
        UUID employeeId,
        List<Row> rows
) {

    /** A single loan/request row for the employee's own view. */
    public record Row(
            String loanAmountFormatted,
            String monthlyInstallmentFormatted,
            String remainingFormatted,
            String progress,            // e.g. "2 / 6 months"
            String startDateFormatted,
            String description,         // may be blank
            String statusName,          // raw enum name
            String statusLabel,         // humanized
            String statusKind,          // success | warn | error | neutral
            String rejectionReason      // shown when REJECTED; blank otherwise
    ) {}
}
