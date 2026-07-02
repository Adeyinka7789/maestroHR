package com.admtechhub.maestrohr.employee.event;

import java.util.UUID;

/**
 * Event published after an employee is successfully persisted.
 * Listeners handle external side effects (Paystack verification, notifications)
 * AFTER the database transaction commits.
 */
public record EmployeeCreatedEvent(
        UUID employeeId,
        String bankName,
        String bankAccountNumber,
        String bankAccountName,
        String rawPassword
) {}