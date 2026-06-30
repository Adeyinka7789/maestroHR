-- Payroll run reversal audit columns
ALTER TABLE payroll_runs
    ADD COLUMN reversed_by  UUID REFERENCES users(id),
    ADD COLUMN reversed_at  TIMESTAMPTZ,
    ADD COLUMN reversal_reason VARCHAR(500);

-- Soft-delete marker on loan repayment rows so reversal leaves an audit trail
ALTER TABLE loan_repayments ADD COLUMN reversed_at TIMESTAMPTZ;
