package com.admtechhub.maestrohr.payroll.event;

import java.util.UUID;

/**
 * In-process event published after a payroll run's approval transaction commits. Listeners
 * (Kafka publish + payslip dispatch fallback, in-app notifications) run AFTER commit — mirrors
 * {@code com.admtechhub.maestrohr.employee.event.EmployeeCreatedEvent}'s pattern.
 */
public record PayrollApprovedAppEvent(
        UUID payrollRunId,
        UUID tenantId,
        String period,
        String approvedByEmail,
        String initiatedByEmail
) {}
