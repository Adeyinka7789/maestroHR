package com.admtechhub.maestrohr.loan;

/**
 * Lifecycle of an {@link EmployeeLoan}. An employee applies (PENDING); HR/Finance approves
 * (→ ACTIVE) or rejects (→ REJECTED).
 *
 * <ul>
 *   <li>{@code PENDING}  — applied for by the employee; awaiting an HR/Finance decision. Inert.</li>
 *   <li>{@code ACTIVE}   — approved; installments are deducted each payroll run.</li>
 *   <li>{@code PAUSED}   — temporarily skipped; no deduction until resumed.</li>
 *   <li>{@code COMPLETED}— remaining balance reached zero; fully repaid.</li>
 *   <li>{@code CANCELLED}— written off / stopped by HR; never deducted again.</li>
 *   <li>{@code REJECTED} — the request was declined; never becomes active.</li>
 * </ul>
 *
 * Only {@code ACTIVE} loans contribute to the payroll loan deduction.
 */
public enum LoanStatus {
    PENDING,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    REJECTED
}
