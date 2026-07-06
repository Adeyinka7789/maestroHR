package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/** Server-rendered view model for {@code /htmx/exit-management} (list + detail). */
public record ExitManagementView(List<Row> rows, Stats stats, List<EmployeeOption> employees) {

    /** Tenant roster for the Initiate Exit form's employee picker; value is the real employee UUID. */
    public record EmployeeOption(UUID id, String label) {}

    public record Row(
            UUID id,
            String employeeName,
            String employeeInitials,
            String department,
            String exitType,
            String lastWorkingDay,
            String status,
            String statusLabel,
            String statusKind,
            int clearedItems,
            int totalItems,
            boolean canSettle,
            boolean canMarkPaid
    ) {}

    public record Stats(
            long inClearance,
            long clearanceComplete,
            long settled,
            long completed,
            String totalPayable
    ) {}
}
