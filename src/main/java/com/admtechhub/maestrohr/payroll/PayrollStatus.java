package com.admtechhub.maestrohr.payroll;

public enum PayrollStatus {
    DRAFT,              // Initial state, can edit
    PENDING_APPROVAL,   // Submitted for approval
    APPROVED,           // Approved by Finance Officer
    DISBURSING,         // Payment in progress
    COMPLETED,          // All payments successful
    REJECTED            // Rejected with comments
}