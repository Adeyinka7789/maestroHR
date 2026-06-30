package com.admtechhub.maestrohr.loan;

public enum RepaymentType {
    /** Normal deduction applied during payroll approval. */
    STANDARD,
    /** HR/Finance write-off — clears the balance without a payroll run. */
    WAIVER,
    /** Reversal — the payroll run was reversed; balance has been restored. */
    REVERSAL
}
