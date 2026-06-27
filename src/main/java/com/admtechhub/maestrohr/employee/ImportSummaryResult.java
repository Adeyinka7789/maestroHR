package com.admtechhub.maestrohr.employee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of the import ("confirm") phase. Each importable row is processed in its own
 * transaction, so these counts reflect what was actually committed — there are no phantom
 * successes: a row counted in {@link #successCount} is persisted, full stop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportSummaryResult {

    /** Rows that were committed to the database. */
    private int successCount;

    /** Rows skipped before any DB attempt because they were invalid in the preview (ERROR). */
    private int skippedCount;

    /** Rows that were attempted but failed at write time (their own transaction rolled back). */
    private int failedCount;

    @Builder.Default
    private List<FailedRow> failures = new ArrayList<>();

    /** Non-fatal advisories (defaults applied, departments created, etc.). */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedRow {
        private int rowNumber;
        private String email;
        private String reason;
    }
}
