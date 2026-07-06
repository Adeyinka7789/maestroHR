-- V53__widen_payroll_status_checks_and_add_run_uniqueness.sql
--
-- Two independent fixes, bundled because both were flagged by the same architecture
-- audit and both are contained, low-regression schema corrections.
--
-- =====================================================================
-- 1. chk_payroll_status / chk_transfer_status were never widened after PayrollStatus
--    and TransferStatus grew new values post-V6. Verified against every .setStatus(...)/
--    .status(...) call site touching these columns (PayrollRunService, DisbursementService,
--    PaymentWebhookService, PayrollRun.builder defaults):
--
--    payroll_runs.status (PayrollStatus) — chk_payroll_status currently allows only
--      DRAFT, PENDING_APPROVAL, APPROVED, DISBURSING, COMPLETED, REJECTED (confirmed via
--      pg_get_constraintdef against the live DB). The code additionally writes:
--        FAILED              - DisbursementService (rollbackRunToFailed, reconciliation paths)
--        DISBURSING_UNKNOWN  - DisbursementService (unknown-state reconciliation)
--        REVERSED            - PayrollRunService.reversePayrollRun
--      All 9 PayrollStatus enum values are included below.
--
--    payroll_entries.transfer_status (TransferStatus) — chk_transfer_status currently
--      allows only PENDING, SUCCESS, FAILED (confirmed via pg_get_constraintdef). The code
--      additionally writes:
--        DISBURSING - DisbursementService (updateRunWithTransferCodes, updateEntriesToDisbursing)
--        PAID       - PayrollRunService.markAsPaid, DisbursementService.disburseSalariesCsv,
--                     PaymentWebhookService (transfer.success webhook)
--        REVERSED   - PayrollRunService.reversePayrollRun
--      SUCCESS is defined on the enum and compared against (PayslipGenerator) but is not
--      currently written by any code path — included anyway so the constraint matches the
--      full Java-level type domain rather than only today's write call sites; a future
--      write of SUCCESS should not require another constraint-widening migration.
--      All 6 TransferStatus enum values are included below.
--
-- =====================================================================
-- 2. No unique constraint existed on payroll_runs(tenant_id, payroll_month, payroll_year) —
--    only the application-level existsBy... check in PayrollRunService.initiatePayroll, which
--    is a check-then-act race under concurrent requests. A partial unique index closes it at
--    the database level, mirroring idx_employee_loans_one_active (V48)'s exact pattern.
--    REJECTED/REVERSED runs are excluded from the constraint so a rejected or reversed run
--    can be superseded by a fresh one for the same period.
--
--    Existing data was checked before this migration was written: zero duplicate
--    (tenant_id, payroll_month, payroll_year) groups with a non-REJECTED/REVERSED status
--    exist in the current database (2 total rows in payroll_runs, no duplicates), so this
--    index applies cleanly.
-- =====================================================================

ALTER TABLE payroll_runs DROP CONSTRAINT IF EXISTS chk_payroll_status;
ALTER TABLE payroll_runs ADD CONSTRAINT chk_payroll_status CHECK (status IN (
    'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'DISBURSING', 'DISBURSING_UNKNOWN',
    'COMPLETED', 'FAILED', 'REJECTED', 'REVERSED'
));

ALTER TABLE payroll_entries DROP CONSTRAINT IF EXISTS chk_transfer_status;
ALTER TABLE payroll_entries ADD CONSTRAINT chk_transfer_status CHECK (transfer_status IN (
    'PENDING', 'SUCCESS', 'DISBURSING', 'PAID', 'FAILED', 'REVERSED'
));

CREATE UNIQUE INDEX idx_payroll_runs_one_active_period
    ON payroll_runs (tenant_id, payroll_month, payroll_year)
    WHERE status NOT IN ('REJECTED', 'REVERSED');
