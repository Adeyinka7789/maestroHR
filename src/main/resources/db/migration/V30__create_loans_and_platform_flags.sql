-- V30__create_loans_and_platform_flags.sql
--
-- Two features in one migration:
--
--   1. Employee loans / salary advances (tenant-scoped). A loan is repaid via a
--      fixed monthly installment deducted during payroll. The deduction is a
--      POST-statutory, post-tax line item — Nigerian loan repayments do not reduce
--      taxable income — so it mirrors unpaid_leave_deduction / attendance_deduction
--      (V21) and a new loan_deduction column is added to payroll_entries.
--
--      Two-phase application (see PayrollEngine / PayrollRunService):
--        * CALCULATE at compute time — idempotent, no balance mutation, so the
--          re-runnable computePayroll never double-charges.
--        * APPLY at approve time — exactly-once. The loan_repayments ledger records
--          which run paid which loan how much, and its UNIQUE(loan_id, payroll_run_id)
--          is the hard idempotency guard against a double-apply.
--
--   2. Global platform feature flags (a Waffle-style kill switch). PLATFORM-GLOBAL,
--      not tenant-scoped — like broadcasts (V28): no tenant_id, no RLS. A SUPER_ADMIN
--      toggles a flag to enable/disable a feature across every tenant. Effective
--      availability becomes: global flag enabled AND tenant's plan includes the
--      feature. A feature with no flag row defaults to enabled (backward compatible),
--      so only LOAN_MANAGEMENT is seeded here.
--
-- RLS: employee_loans and loan_repayments are tenant tables and get the standard
-- V20/V25 NULLIF tenant_isolation policy. platform_flags gets none. The maestro_app
-- (NOBYPASSRLS) role receives DML on all three via V24's ALTER DEFAULT PRIVILEGES.

-- =====================================================================
-- 1. employee_loans
-- =====================================================================

CREATE TABLE employee_loans (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    employee_id         UUID NOT NULL REFERENCES employees(id),
    loan_amount         BIGINT NOT NULL,           -- kobo
    monthly_installment BIGINT NOT NULL,           -- kobo (loan_amount / repayment_months)
    remaining_balance   BIGINT NOT NULL,           -- kobo, decreases each applied month
    repayment_months    INT NOT NULL,
    months_paid         INT NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/PAUSED/COMPLETED/CANCELLED
    start_date          DATE NOT NULL,
    description         VARCHAR(500),
    created_by          VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Active-loan lookup during payroll compute, and the per-employee loan list.
CREATE INDEX idx_employee_loans_employee ON employee_loans (tenant_id, employee_id, status);
CREATE INDEX idx_employee_loans_tenant   ON employee_loans (tenant_id, status);

ALTER TABLE employee_loans ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_loans
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 2. loan_repayments — the exactly-once apply ledger
-- =====================================================================

CREATE TABLE loan_repayments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    loan_id         UUID NOT NULL REFERENCES employee_loans(id),
    payroll_run_id  UUID NOT NULL REFERENCES payroll_runs(id),
    amount          BIGINT NOT NULL,               -- kobo applied to the loan in this run
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One repayment per loan per run: the hard guard against double-apply on a
    -- re-approval / retry. The apply path inserts here first, so a second attempt
    -- hits this constraint instead of decrementing the balance twice.
    CONSTRAINT uk_loan_repayment_per_run UNIQUE (loan_id, payroll_run_id)
);

CREATE INDEX idx_loan_repayments_run  ON loan_repayments (payroll_run_id);
CREATE INDEX idx_loan_repayments_loan ON loan_repayments (loan_id);

ALTER TABLE loan_repayments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan_repayments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 3. payroll_entries.loan_deduction — the per-entry line item (kobo)
-- =====================================================================

ALTER TABLE payroll_entries
    ADD COLUMN loan_deduction BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN payroll_entries.loan_deduction IS
    'Kobo deducted for active loan repayments in the payroll period: sum of min(monthly_installment, remaining_balance) over the employee''s ACTIVE loans. Calculated idempotently at compute; the matching balance decrement is applied once at approval via loan_repayments.';

-- =====================================================================
-- 4. platform_flags — global, platform-wide feature kill switches
-- =====================================================================

CREATE TABLE platform_flags (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    description VARCHAR(500),
    updated_by  VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed the LOAN_MANAGEMENT flag enabled (every other feature stays unflagged and
-- thus defaults to enabled). Idempotent so a re-run is a no-op.
INSERT INTO platform_flags (name, enabled, description, updated_by)
VALUES ('LOAN_MANAGEMENT', true, 'Employee loan management (loans & salary advances repaid via payroll)', 'system')
ON CONFLICT (name) DO NOTHING;
