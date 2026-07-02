-- Add batch reference for tracking bulk transfer requests to Paystack
-- Used for idempotency and reconciliation when network timeouts occur
ALTER TABLE payroll_runs
ADD COLUMN IF NOT EXISTS batch_reference VARCHAR(100);

-- Add Paystack's transfer code for individual entries
-- Different from our internal transfer_reference - this is Paystack's identifier
ALTER TABLE payroll_entries
ADD COLUMN IF NOT EXISTS paystack_transfer_code VARCHAR(100);

-- Add index on batch_reference for faster reconciliation queries
-- Using CONCURRENTLY to avoid table locks in production
CREATE INDEX IF NOT EXISTS idx_payroll_runs_batch_reference
ON payroll_runs(batch_reference);

-- Add index on paystack_transfer_code for faster lookups during webhook processing
CREATE INDEX IF NOT EXISTS idx_payroll_entries_paystack_transfer_code
ON payroll_entries(paystack_transfer_code);