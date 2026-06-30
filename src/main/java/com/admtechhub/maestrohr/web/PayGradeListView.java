package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned pay-grades list fragment
 * (templates/pay-grades.html). Like the departments list it is assembled once on
 * the server from a single tenant-scoped query (no client-side JSON round-trips,
 * no loading flash), but the pay-grades page keeps the richer card-grid layout:
 * a summary strip (total grades / highest gross / average gross) plus one card per
 * grade with its salary components, assigned headcount, and a relative-pay bar.
 *
 * All monetary values are pre-formatted naira strings (the entity stores kobo).
 * The summary stats and each card's {@code relativePct} are derived in
 * {@link PayGradeListService} from the rows returned for the current view, so the
 * template stays presentation-only. Mirrors {@link DepartmentListView}.
 */
public record PayGradeListView(
        List<Row> rows,
        int totalGrades,              // number of grades in the current (possibly searched) view
        String highestGrossFormatted, // e.g. "₦510,000"
        String avgGrossFormatted,     // e.g. "₦285,000"
        String search                 // active search term, or null
) {

    /** A single rendered pay-grade card. */
    public record Row(
            UUID id,
            String name,
            boolean active,
            long employeeCount,         // employees currently assigned to this grade
            long basicSalary,           // kobo — for edit modal data-* attributes
            long housingAllowance,      // kobo
            long transportAllowance,    // kobo
            long otherAllowances,       // kobo
            String basicFormatted,
            String housingFormatted,
            String transportFormatted,
            String otherFormatted,
            String grossFormatted,
            int relativePct,            // gross as a % of the highest gross in this view (0–100)
            UUID loanPolicyId           // null when no policy is linked to this grade
    ) {}
}
