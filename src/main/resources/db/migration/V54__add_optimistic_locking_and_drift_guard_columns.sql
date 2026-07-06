-- V54__add_optimistic_locking_and_drift_guard_columns.sql
--
-- Two independent fixes, bundled because both were flagged by the same architecture audit
-- and both are additive, low-regression schema corrections.
--
-- =====================================================================
-- 1. Optimistic locking (@Version) for the three money/state-bearing entities with no
--    lost-update protection: concurrent approvePayroll + waiveLoan, two approvers racing
--    the DRAFT->PENDING_APPROVAL->APPROVED state machine, concurrent leave-balance
--    deductions. Hibernate's @Version requires a matching NOT NULL column.
-- =====================================================================

ALTER TABLE payroll_runs   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE employee_loans ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE leave_balances ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- =====================================================================
-- 2. payroll_entries: two new columns for the PayrollEngine correctness fixes.
--
--    net_floor_clamped: flags an entry whose net salary would have gone negative (heavy
--    unpaid-leave/absence/late deductions, or an uncapped loan on a tenant with no
--    LoanPolicy configured) before the platform-wide minimum-wage floor and non-negative
--    clamp — now applied unconditionally, not only when a LoanPolicy exists — stepped in.
--    Mirrors loan_deduction_capped's existing pattern (V41).
--
--    deduction_snapshot: a compute-time snapshot of "unpaidLeaveDays:absentDays:lateDays:
--    loanDeduction" for the entry's employee/period. Re-verified in full at approval
--    (replacing the loan-only, capped-entry-skipping check in LoanService) so any drift in
--    ANY of the four inputs between compute and approval - including on a capped entry -
--    is caught, not just a loan-deduction mismatch on an uncapped one. Nullable: existing
--    entries computed before this guard existed have no snapshot to compare against, so the
--    check skips them (logged) rather than failing a payroll run that predates the fix.
-- =====================================================================

ALTER TABLE payroll_entries ADD COLUMN net_floor_clamped BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payroll_entries ADD COLUMN deduction_snapshot VARCHAR(100);

COMMENT ON COLUMN payroll_entries.net_floor_clamped IS
    'TRUE when the platform-wide minimum-wage net floor / non-negative clamp reduced the '
    'loan deduction or floored net salary at 0 for this entry, independent of whether a '
    'LoanPolicy is configured.';
COMMENT ON COLUMN payroll_entries.deduction_snapshot IS
    'Compute-time snapshot "unpaidLeaveDays:absentDays:lateDays:loanDeduction" for this '
    'entry''s employee/period, re-verified in full at approval to detect drift.';
