-- V65__cost_centers_and_gl.sql
--
-- Multi-branch / cost-center accounting + GL journal export. Two new tenant-scoped tables plus an
-- optional cost_center dimension on employees, so a payroll run can be exported as balanced,
-- ERP-ready journal entries (expense debits attributed per branch; consolidated statutory credits).

-- =====================================================================
-- 1. cost_centers  (branches / departments-for-accounting)
-- =====================================================================

CREATE TABLE cost_centers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    code            VARCHAR(40)  NOT NULL,
    location        VARCHAR(120),
    -- Optional GL expense account for this cost center's salary expense; falls back to the
    -- tenant's default salary-expense account (gl_account_configs) when blank.
    gl_account_code VARCHAR(60),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_cost_center_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_cost_centers_tenant ON cost_centers (tenant_id, is_active);

ALTER TABLE cost_centers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cost_centers
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Optional cost-center tag on the employee (ON DELETE SET NULL: retiring a cost center never
-- deletes staff, it just unassigns them).
ALTER TABLE employees
    ADD COLUMN cost_center_id UUID REFERENCES cost_centers(id) ON DELETE SET NULL;

CREATE INDEX idx_employees_cost_center ON employees (cost_center_id) WHERE cost_center_id IS NOT NULL;

-- =====================================================================
-- 2. gl_account_configs  (one per tenant — the standard journal account codes)
-- =====================================================================

CREATE TABLE gl_account_configs (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    salary_expense_account     VARCHAR(60) NOT NULL DEFAULT '6000',
    pension_expense_account    VARCHAR(60) NOT NULL DEFAULT '6100',
    net_pay_account            VARCHAR(60) NOT NULL DEFAULT '2000',
    paye_payable_account       VARCHAR(60) NOT NULL DEFAULT '2100',
    pension_payable_account    VARCHAR(60) NOT NULL DEFAULT '2200',
    nhf_payable_account        VARCHAR(60) NOT NULL DEFAULT '2300',
    other_deductions_account   VARCHAR(60) NOT NULL DEFAULT '2400',
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_gl_config_tenant UNIQUE (tenant_id)
);

ALTER TABLE gl_account_configs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON gl_account_configs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
