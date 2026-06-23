package com.admtechhub.maestrohr.web;

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

        // Bank details
        String bankName,
        String bankAccountNumber,
        String bankAccountName,
        String paystackRecipientCode,

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
        boolean canHardDelete      // SUPER_ADMIN and the employee has no dependent records
) {}
