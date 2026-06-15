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
            String createdFormatted   // e.g. "02 Jun 2025" or "—"
    ) {}
}
