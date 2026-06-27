package com.admtechhub.maestrohr.employee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-row outcome returned by the import <em>preview</em> phase. Carries the parsed,
 * human-readable values so the frontend can render a colour-coded table (green = VALID,
 * yellow = WARNING, red = ERROR) together with the messages explaining each flag.
 *
 * <p>This is a display projection only — no database identifiers are exposed. The actual
 * import re-parses the uploaded file (see {@code EmployeeImportService}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportRowResult {

    /** 1-based row number as it appears in the file, excluding the header row. */
    private int rowNumber;

    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String jobTitle;
    private String employmentType;
    private String department;
    private String payGrade;

    private ImportRowStatus status;

    @Builder.Default
    private List<String> messages = new ArrayList<>();
}
