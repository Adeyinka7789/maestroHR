package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * View model for the server-rendered Reports page (templates/reports.html).
 * Carries server-populated dropdown options and sensible filter defaults.
 */
public record ReportsListView(
        List<EmployeeOption> employees,
        List<PayrollRunOption> payrollRuns,
        int defaultMonth,
        int defaultYear,
        String defaultFromDate,
        String defaultToDate
) {
    public record EmployeeOption(UUID id, String label) {}
    public record PayrollRunOption(UUID id, String label) {}
}
