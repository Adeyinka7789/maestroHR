package com.admtechhub.maestrohr.payment;

/**
 * Outcome of a single payment attempt recorded on an {@link Invoice}.
 */
public enum PaymentStatus {
    /** Initialized with Paystack, awaiting a charge result. */
    PENDING,
    /** Charge confirmed successful (via verification or webhook). */
    SUCCESS,
    /** Charge failed or was abandoned. */
    FAILED
}
