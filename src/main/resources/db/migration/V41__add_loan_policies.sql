-- V41: Loan policies — per-pay-grade or per-tenant eligibility / limit rules.
--
-- Changes:
--   1. loan_policies      — tenant-scoped policy table with RLS
--   2. pay_grades         — optional FK loan_policy_id (nullable → falls back to tenant default)
--   3. employee_loans     — interest_rate_pct, loan_policy_id snapshot, waiver fields
--   4. loan_repayments    — repayment_type (STANDARD | WAIVER), payroll_run_id nullable for waivers
--   5. payroll_entries    — loan_deduction_capped flag (set when net-floor protection kicked in)
--   6. platform_settings  — four global ceiling keys that cap policy limits

-- ─── 1. loan_policies ─────────────────────────────────────────────────────────
CREATE TABLE loan_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    name                    VARCHAR(100) NOT NULL,
    description             VARCHAR(500),
    min_amount_kobo         BIGINT NOT NULL DEFAULT 0,
    max_amount_kobo         BIGINT NOT NULL,
    max_multiplier_of_gross NUMERIC(4,2) NOT NULL DEFAULT 3.0,
    max_deduction_pct       NUMERIC(5,2) NOT NULL DEFAULT 33.0,
    net_floor_pct           NUMERIC(5,2) NOT NULL DEFAULT 50.0,
    max_tenor_months        INTEGER NOT NULL DEFAULT 12,
    interest_rate_pct       NUMERIC(5,2) NOT NULL DEFAULT 0.0,
    cooling_period_days     INTEGER NOT NULL DEFAULT 30,
    max_loans_per_year      INTEGER NOT NULL DEFAULT 2,
    approval_threshold_kobo BIGINT NOT NULL DEFAULT 10000000,
    eligible_after_months   INTEGER NOT NULL DEFAULT 6,
    probation_eligible      BOOLEAN NOT NULL DEFAULT FALSE,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ DEFAULT now(),
    updated_at              TIMESTAMPTZ DEFAULT now(),
    deleted_at              TIMESTAMPTZ
);

CREATE INDEX idx_loan_policies_tenant ON loan_policies (tenant_id, is_active) WHERE deleted_at IS NULL;

ALTER TABLE loan_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan_policies
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ─── 2. pay_grades: optional policy link ──────────────────────────────────────
ALTER TABLE pay_grades ADD COLUMN loan_policy_id UUID REFERENCES loan_policies(id);

-- ─── 3. employee_loans additions ──────────────────────────────────────────────
ALTER TABLE employee_loans ADD COLUMN interest_rate_pct NUMERIC(5,2) NOT NULL DEFAULT 0.0;
ALTER TABLE employee_loans ADD COLUMN loan_policy_id UUID REFERENCES loan_policies(id);
ALTER TABLE employee_loans ADD COLUMN waiver_reason VARCHAR(500);
ALTER TABLE employee_loans ADD COLUMN waived_at TIMESTAMPTZ;
ALTER TABLE employee_loans ADD COLUMN waived_by VARCHAR(255);

-- ─── 4. loan_repayments: type + nullable payroll_run_id for WAIVER rows ───────
ALTER TABLE loan_repayments ADD COLUMN repayment_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE loan_repayments ALTER COLUMN payroll_run_id DROP NOT NULL;

COMMENT ON COLUMN loan_repayments.repayment_type IS
    'STANDARD = normal payroll deduction; WAIVER = HR write-off. Waiver rows have payroll_run_id = NULL.';

-- ─── 5. payroll_entries: net-floor cap flag ───────────────────────────────────
ALTER TABLE payroll_entries ADD COLUMN loan_deduction_capped BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN payroll_entries.loan_deduction_capped IS
    'TRUE when the loan deduction was reduced to protect the employee''s net salary floor. '
    'applyRepaymentsForRun respects entry.loanDeduction as the budget when this is TRUE.';

-- ─── 6. Platform ceiling settings ─────────────────────────────────────────────
INSERT INTO platform_settings (key, value) VALUES
    ('loan_max_multiplier_ceiling',  '6'),
    ('loan_min_net_floor_pct',       '33'),
    ('loan_max_tenor_months_ceiling','36'),
    ('loan_max_deduction_pct_ceiling','50')
ON CONFLICT (key) DO NOTHING;
