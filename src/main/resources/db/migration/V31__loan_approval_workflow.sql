-- V31__loan_approval_workflow.sql
--
-- Loans become an employee-driven request workflow: an employee APPLIES for a loan
-- (status PENDING), and HR / Finance approves it (→ ACTIVE, starts deducting via payroll)
-- or rejects it (→ REJECTED, never deducts). The deduction engine only ever touches
-- ACTIVE loans, so PENDING/REJECTED requests are inert until approved.
--
-- Adds the approval-tracking columns and flips the default status to PENDING (a new loan
-- is a request, not an active deduction). The status column is already VARCHAR(20), so the
-- new PENDING / REJECTED values need no DDL.

ALTER TABLE employee_loans
    ADD COLUMN decided_by       VARCHAR(255),   -- email of the HR/Finance approver or rejecter
    ADD COLUMN decided_at       TIMESTAMPTZ,    -- when the approve/reject decision was made
    ADD COLUMN rejection_reason VARCHAR(500);   -- reason captured on rejection

ALTER TABLE employee_loans ALTER COLUMN status SET DEFAULT 'PENDING';

COMMENT ON COLUMN employee_loans.status IS
    'PENDING (applied, awaiting decision) / ACTIVE (approved, deducting) / PAUSED / COMPLETED / CANCELLED / REJECTED. Only ACTIVE loans are deducted in payroll.';
COMMENT ON COLUMN employee_loans.decided_by IS
    'Email of the HR/Finance user who approved or rejected the request';
COMMENT ON COLUMN employee_loans.rejection_reason IS
    'Reason recorded when a loan request is rejected';
