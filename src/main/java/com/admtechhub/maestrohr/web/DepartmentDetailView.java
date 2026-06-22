package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the department detail page
 * ({@code /htmx/departments/{id}}), rendered as {@code department-detail :: content}.
 *
 * Assembled once on the server from the department row plus its employee roster
 * (no client-side fetches). The headcount metrics and the monthly payroll cost are
 * derived in {@link DepartmentDetailService} from the in-memory employee list:
 * payroll cost is the sum of each ACTIVE employee's pay-grade gross salary (NOT a
 * payroll-run figure), so it reflects current establishment cost regardless of
 * whether payroll has been run.
 */
public record DepartmentDetailView(
        UUID id,
        String name,
        String headName,                 // "Not assigned" until head assignment ships
        String createdFormatted,         // e.g. "02 Jun 2025" or "—"
        long totalEmployees,
        long activeEmployees,
        long onLeave,
        String monthlyPayrollFormatted,  // e.g. "₦4,250,000"
        List<Row> employees
) {

    /** A single rendered roster row. */
    public record Row(
            UUID id,
            String fullName,
            String jobTitle,
            String payGradeName,
            String startDateFormatted,   // e.g. "02 Jun 2025" or "—"
            String statusName,           // raw enum name, e.g. "ON_LEAVE"
            String statusLabel,          // humanized, e.g. "On Leave"
            String statusKind            // badge colour bucket: success/warn/error/neutral
    ) {}
}
