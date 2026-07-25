package com.admtechhub.maestrohr.recruitment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projections for the public careers portal. These are populated by
 * {@link CareersPublicRepository} over the privileged (RLS-bypassing) datasource, because the
 * public page has no tenant session and the recruitment entities are {@code @SQLRestriction}-scoped.
 * They deliberately expose only the public-safe columns — never the internal applicant pipeline.
 */
public final class CareersView {

    private CareersView() {
    }

    /** The hiring company as shown on its public careers landing page. */
    public record Company(
            UUID id,
            String companyName,
            String logoUrl,
            String careersIntro,
            boolean careersEnabled,
            boolean active) {
    }

    /** A single PUBLISHED job posting, public-safe fields only. */
    public record Job(
            UUID id,
            String title,
            String department,
            String location,
            String employmentType,
            Long salaryRangeMin,
            Long salaryRangeMax,
            String description,
            String requirements,
            String benefits,
            LocalDate postedDate,
            LocalDate closingDate) {
    }

    /** Convenience bundle for rendering a landing page in one shot. */
    public record Listing(Company company, List<Job> jobs) {
    }
}
