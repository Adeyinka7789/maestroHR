package com.admtechhub.maestrohr.employee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of the parse + validate ("preview") phase. Performs <strong>no database writes</strong>.
 * The frontend renders this for the user to review before confirming the import.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreviewResult {

    /**
     * Set when the file cannot be processed at all (e.g. missing required columns, empty file,
     * too many rows). When non-null, {@link #rows} is empty and nothing should be imported.
     */
    private String fatalError;

    /** Detected header → canonical field mapping, e.g. {@code {"Work Email": "email"}}. */
    @Builder.Default
    private Map<String, String> columnMapping = new LinkedHashMap<>();

    /** Header names present in the file that were not recognised and will be ignored. */
    @Builder.Default
    private List<String> ignoredColumns = new ArrayList<>();

    /** Department names referenced in the file that do not yet exist for this tenant. */
    @Builder.Default
    private List<String> unknownDepartments = new ArrayList<>();

    /** File-level advisories not tied to a single row (e.g. ">200 rows"). */
    @Builder.Default
    private List<String> globalWarnings = new ArrayList<>();

    @Builder.Default
    private List<ImportRowResult> rows = new ArrayList<>();

    private int totalRows;
    private int validCount;
    private int warningCount;
    private int errorCount;

    /** Convenience flag: how many rows would actually be imported (VALID + WARNING). */
    private int importableCount;
}
