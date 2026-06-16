package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the employee self-service "My Payslips" page
 * ({@code templates/payslips.html}, rendered by {@link PayslipSelfController}).
 * Built per request from the authenticated employee's own payroll entries — the
 * list only ever contains the session employee's runs, and {@link #employeeId}
 * is the only id the download links carry (never trusted from a request param).
 *
 * Mirrors {@link AttendanceSelfView} / {@link LeaveListView}: a flat record the
 * Thymeleaf fragment iterates with no client-side fetch. Read-only — the PDF
 * itself is produced by the existing {@code /reports/payslip} endpoint.
 *
 * Only runs the employee should see are listed (APPROVED / DISBURSING / COMPLETED);
 * DRAFT, PENDING_APPROVAL and REJECTED runs are filtered out in the service.
 */
public record PayslipSelfView(
        String employeeName,   // the authenticated employee's full name
        UUID employeeId,       // the session employee's own id — used in every download link
        List<Row> rows         // newest run first; empty -> empty-state card
) {
    /** One payslip row: a single payroll run the employee has an entry in. */
    public record Row(
            UUID payrollRunId,     // target of the /reports/payslip download link
            String periodFormatted,// e.g. "June 2026"
            String netPayFormatted,// e.g. "₦450,000.00"
            String statusLabel,    // "Paid" | "Approved" | "Disbursing"
            String statusKind      // success | warn | neutral -> badge colour
    ) {}
}
