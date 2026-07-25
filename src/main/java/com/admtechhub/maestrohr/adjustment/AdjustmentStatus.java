package com.admtechhub.maestrohr.adjustment;

/**
 * Lifecycle of a logged adjustment. PENDING items are eligible to be consumed by the next
 * payroll run for their period; APPLIED means a run has consumed the item (and carries the run
 * id); CANCELLED means HR voided it before it was applied. A run reversal moves its APPLIED
 * items back to PENDING so they re-enter the next run — mirroring loan-repayment reversal.
 */
public enum AdjustmentStatus {
    PENDING,
    APPLIED,
    CANCELLED
}
