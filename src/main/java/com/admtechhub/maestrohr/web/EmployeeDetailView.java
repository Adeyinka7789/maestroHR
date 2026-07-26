package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the employee detail page
 * ({@code /htmx/employee-view?id=...}), rendered as {@code employee-detail :: content}.
 *
 * Assembled once on the server from the employee record (plus its eagerly-touched
 * department and pay grade) in {@link EmployeeDetailService} — no client-side
 * fetches. Every field is pre-formatted for direct display: dates as
 * {@code "02 Jun 2025"} (or {@code "—"}), salaries as {@code "₦450,000"}, and enum
 * codes humanized (e.g. {@code FULL_TIME} → {@code "Full Time"}). Mirrors
 * {@link DepartmentDetailView}.
 *
 * The salary section reflects the employee's current pay grade (establishment cost),
 * not a payroll-run figure. {@code hasPayGrade} is false when no grade is assigned,
 * in which case the breakdown fields are {@code null} and the template shows an
 * empty state instead.
 */
public record EmployeeDetailView(
        UUID id,
        String fullName,
        String jobTitle,
        String employeeNumber,
        String statusName,           // raw enum name, e.g. "ON_LEAVE"
        String statusLabel,          // humanized, e.g. "On Leave"
        String statusKind,           // badge colour bucket: success/warn/error/neutral

        // Personal information
        String email,
        String phone,
        String dateOfBirthFormatted,
        String gender,
        String maritalStatus,
        String address,

        // Employment details
        String departmentName,
        String employmentType,
        String startDateFormatted,
        String probationEndFormatted,
        String shiftDisplay,          // shift name, "No shift assigned — using tenant default", or "No shift configured"
        String estimatedRetirementDateFormatted, // or "Not available (date of birth not on file)"

        // Bank details
        String bankName,
        String bankAccountNumber,
        String bankAccountName,
        String paystackRecipientCode,
        boolean hasBankDetails,   // bankName + bankAccountNumber both present
        boolean recipientLinked,  // paystackRecipientCode present

        // Salary (current pay grade)
        boolean hasPayGrade,
        String payGradeName,
        String basicFormatted,
        String housingFormatted,
        String transportFormatted,
        String grossFormatted,

        // Summary
        String joinedFormatted,
        String createdFormatted,

        // Action permissions (computed from the viewer's roles + the employee's state),
        // so a destructive button is only rendered when the action would actually succeed.
        boolean canTerminate,      // HR_ADMIN/SUPER_ADMIN and not already terminated
        boolean canHardDelete,     // SUPER_ADMIN and the employee has no dependent records

        // Loans — read-only summary of this employee's loans (newest first), or empty.
        List<LoanSummary> loans,

        // Probation → Confirmation state (see V62). An ACTIVE employee with a probation end
        // date and no confirmation is "on probation"; confirming them stamps confirmedFormatted.
        boolean onProbation,
        boolean confirmed,
        String confirmedFormatted,       // "12 Aug 2026" once confirmed, else "—"
        String confirmedBy,              // actor email once confirmed, else "—"
        String probationStateLabel,      // "On Probation" / "Confirmed" / "—"
        String probationStateKind,       // badge bucket: warn / success / neutral
        boolean canConfirm,              // HR/super-admin AND the employee is on probation

        // Fixed-term contract end date (see V63). "Permanent" when none is set.
        String contractEndFormatted,

        // Cost center / branch name (see V65), or "Unassigned".
        String costCenterDisplay
) {

    /** A single loan summarised for the employee detail page; money pre-formatted as naira. */
    public record LoanSummary(
            String amountFormatted,
            String monthlyFormatted,
            String remainingFormatted,
            String progress,          // e.g. "2 / 6 months"
            String startDateFormatted,
            String description,       // may be blank
            String statusLabel,       // humanized, e.g. "Active"
            String statusKind         // success | warn | error | neutral
    ) {}
}
