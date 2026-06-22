package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned departments list fragment.
 * Departments are few per tenant, so unlike the employees list there is no
 * pagination and no department/status filters — just an optional name search.
 * Assembled once on the server from a single tenant-scoped query (no client-side
 * JSON round-trips, no loading flash). Mirrors {@link EmployeeListView}.
 */
public record DepartmentListView(
        List<Row> rows,
        long totalElements,   // number of departments matching the current search
        String search         // active search term, or null
) {

    /** A single rendered department row. */
    public record Row(
            UUID id,
            String name,
            long employeeCount,
            String createdFormatted,        // e.g. "02 Jun 2025" or "—"
            String headEmployeeId,          // currently assigned HOD (UUID string) or null; pre-selects the edit modal
            String departmentEmployeesJson  // THIS department's members as a JSON array
                                            // [{"id","label"},…], embedded in the row's data-employees
                                            // attribute so the modal's HOD dropdown can be populated
                                            // client-side on Edit without a separate API call
    ) {}

    /** A selectable employee for the Head-of-Department dropdown. */
    public record EmployeeOption(
            UUID id,
            String label   // e.g. "Ada Lovelace (EMP-001)"
    ) {}
}
