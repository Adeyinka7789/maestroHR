package com.admtechhub.maestrohr.payroll;

public enum PayrollStatus {
    DRAFT,                // Initial state, can edit
    PENDING_APPROVAL,     // Submitted for approval
    APPROVED,             // Approved by Finance Officer
    DISBURSING,           // Payment in progress
    DISBURSING_UNKNOWN,   // Network timeout - payment state uncertain
    COMPLETED,            // All payments successful
    FAILED,               // Payment execution failed (technical/API error) - can retry
    REJECTED,             // Rejected with comments (human decision)
    REVERSED              // Reversed by SYSTEM_ADMIN/FINANCE_OFFICER; loan ledger rolled back
}