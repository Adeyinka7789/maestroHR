package com.admtechhub.maestrohr.payroll.event;

import java.util.UUID;

/**
 * In-process event published after a payroll run's markAsPaid transaction commits. Listeners
 * (per-entry salary-processed notifications, in-app notification) run AFTER commit — mirrors
 * {@code com.admtechhub.maestrohr.employee.event.EmployeeCreatedEvent}'s pattern.
 */
public record PayrollMarkedPaidAppEvent(
        UUID payrollRunId,
        UUID tenantId,
        String period,
        String companyName,
        String initiatedByEmail
) {}
