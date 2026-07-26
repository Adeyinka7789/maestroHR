package com.admtechhub.maestrohr.overtime;

/** Lifecycle of a computed overtime entry. */
public enum OvertimeStatus {
    /** Computed from attendance, awaiting HR review. */
    DRAFT,
    /** Approved — has emitted a PENDING payroll adjustment the run will consume. */
    APPROVED,
    /** Rejected — any emitted (still-PENDING) adjustment was cancelled. */
    REJECTED
}
