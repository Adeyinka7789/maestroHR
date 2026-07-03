-- V48__add_one_active_loan_per_employee_constraint.sql
--
-- Data-integrity backstop for the "one ACTIVE loan per employee" invariant. Until now this
-- was enforced only in the application layer (LoanPolicyService.validateLoanRequest, gated
-- behind a configured LoanPolicy) and was bypassable via:
--   1. A tenant with no LoanPolicy configured — validateLoanRequest short-circuits entirely.
--   2. Two loans filed while both were PENDING (the check only looks at ACTIVE loans), both
--      later approved via LoanService.approveLoan with no re-check.
--   3. LoanService.resumeLoan flipping a PAUSED loan back to ACTIVE with no re-check, while
--      another loan for the same employee is already ACTIVE.
--   4. A race between two concurrent application-layer checks (no serialization point).
--
-- A partial unique index on (tenant_id, employee_id) WHERE status = 'ACTIVE' closes all four
-- at the database level: at most one ACTIVE row per employee, tenant-scoped, enforced
-- atomically regardless of which code path (or a direct DB write) produced the second row.
-- Existing data was checked before this migration was written (see project audit) and had
-- zero duplicate-ACTIVE rows, so this index applies cleanly.

CREATE UNIQUE INDEX idx_employee_loans_one_active
    ON employee_loans (tenant_id, employee_id)
    WHERE status = 'ACTIVE';
