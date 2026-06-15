package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.EmployeeStatus;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned employees list fragment
 * (templates/employees.html). The page and its search/department/status filters
 * are all assembled once on the server from a single tenant-scoped query — no
 * client-side JSON round-trips, no loading flash. Mirrors the dashboard pilot
 * (see {@link DashboardView}).
 */
public record EmployeeListView(
        List<Row> rows,
        long totalElements,
        int page,            // 0-based current page
        int totalPages,
        boolean hasPrev,
        boolean hasNext,
        int fromIndex,       // 1-based index of the first row on this page (0 when empty)
        int toIndex,         // 1-based index of the last row on this page
        String search,       // active search term, or null
        UUID departmentId,   // active department filter, or null
        EmployeeStatus status,
        List<DepartmentOption> departments,
        List<StatusOption> statuses
) {

    /** A single rendered employee row. */
    public record Row(
            UUID id,
            String initials,
            String fullName,
            String email,
            String employeeNumber,
            String departmentName,
            String payGradeName,
            String statusName,        // raw enum name, e.g. "ON_LEAVE"
            String statusKind,        // success | warn | error | neutral -> badge colour
            String salaryFormatted    // e.g. "₦450,000" or "—"
    ) {}

    /** An entry in the department filter dropdown. */
    public record DepartmentOption(UUID id, String name, boolean selected) {}

    /** An entry in the status filter dropdown. */
    public record StatusOption(String value, String label, boolean selected) {}
}
