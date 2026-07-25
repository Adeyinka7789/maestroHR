-- V61__payroll_adjustments.sql
--
-- Statutory Reimbursements & Deductions Manager: a typed catalogue of one-off payroll
-- adjustments (bonuses, reimbursements, fines, advances, voluntary pension) that HR/Finance
-- log against an employee for a given month, and which the next payroll run for that period
-- automatically consumes — mirroring how employee loans plug into a run.
--
-- Two tables (both tenant-scoped, standard V20/V25 NULLIF RLS; maestro_app gets DML via V24's
-- ALTER DEFAULT PRIVILEGES), plus new payslip line-item columns on payroll_entries.
--
-- Tax treatment is carried by the TYPE, so the payroll engine knows how each adjustment hits
-- the calculation:
--   EARNING  + TAXABLE      → added to gross AND taxed at the marginal band (e.g. Performance Bonus)
--   EARNING  + NON_TAXABLE  → added to gross/net, not taxed (e.g. Transport Reimbursement)
--   DEDUCTION + PRE_TAX     → reduces taxable income (relief) and net (e.g. Voluntary Pension / AVC)
--   DEDUCTION + POST_TAX    → reduces net only, floor-protected like loans (e.g. Late Fine, Advance)
--
-- Default types are seeded lazily per tenant by PayrollAdjustmentService (authenticated,
-- tenant-bound context), not here, so this migration stays DDL-only.

-- =====================================================================
-- 1. adjustment_types  (per-tenant catalogue)
-- =====================================================================

CREATE TABLE adjustment_types (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name           VARCHAR(120) NOT NULL,
    code           VARCHAR(60)  NOT NULL,
    direction      VARCHAR(20)  NOT NULL,   -- EARNING | DEDUCTION
    tax_treatment  VARCHAR(20)  NOT NULL,   -- TAXABLE | NON_TAXABLE | PRE_TAX | POST_TAX
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    is_system      BOOLEAN NOT NULL DEFAULT FALSE,   -- seeded default (name/direction/tax locked)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_adjustment_type_code UNIQUE (tenant_id, code),
    CONSTRAINT chk_adjustment_direction CHECK (direction IN ('EARNING','DEDUCTION')),
    -- The valid tax treatments differ by direction: earnings are (non)taxable, deductions are
    -- pre/post tax. Enforced here so a bad combination can never reach the engine.
    CONSTRAINT chk_adjustment_tax_treatment CHECK (
        (direction = 'EARNING'   AND tax_treatment IN ('TAXABLE','NON_TAXABLE'))
     OR (direction = 'DEDUCTION' AND tax_treatment IN ('PRE_TAX','POST_TAX'))
    )
);

CREATE INDEX idx_adjustment_types_tenant ON adjustment_types (tenant_id, is_active);

ALTER TABLE adjustment_types ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON adjustment_types
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 2. payroll_adjustments  (per-employee, per-period entries)
-- =====================================================================

CREATE TABLE payroll_adjustments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    employee_id        UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    adjustment_type_id UUID NOT NULL REFERENCES adjustment_types(id),
    amount_kobo        BIGINT NOT NULL,
    period_month       INT NOT NULL,        -- 1..12
    period_year        INT NOT NULL,
    note               VARCHAR(500),
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING | APPLIED | CANCELLED
    payroll_run_id     UUID REFERENCES payroll_runs(id) ON DELETE SET NULL,  -- set when consumed by a run
    applied_at         TIMESTAMPTZ,
    created_by         VARCHAR(255),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_adjustment_amount_positive CHECK (amount_kobo > 0),
    CONSTRAINT chk_adjustment_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT chk_adjustment_status CHECK (status IN ('PENDING','APPLIED','CANCELLED'))
);

-- Backs the batch compute for a run: pending adjustments for a tenant's employees in a period.
CREATE INDEX idx_payroll_adjustments_compute
    ON payroll_adjustments (tenant_id, period_year, period_month, status);
CREATE INDEX idx_payroll_adjustments_employee
    ON payroll_adjustments (tenant_id, employee_id, period_year, period_month);
CREATE INDEX idx_payroll_adjustments_run
    ON payroll_adjustments (payroll_run_id) WHERE payroll_run_id IS NOT NULL;

ALTER TABLE payroll_adjustments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payroll_adjustments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 3. payroll_entries: new adjustment line items (all kobo, default 0 for existing rows)
-- =====================================================================

ALTER TABLE payroll_entries
    ADD COLUMN taxable_earnings     BIGINT  NOT NULL DEFAULT 0,   -- taxable EARNING adjustments (added to gross + marginal tax)
    ADD COLUMN non_taxable_earnings BIGINT  NOT NULL DEFAULT 0,   -- non-taxable EARNING adjustments (added to gross, not taxed)
    ADD COLUMN pretax_deduction     BIGINT  NOT NULL DEFAULT 0,   -- PRE_TAX DEDUCTION adjustments (relief + reduces net)
    ADD COLUMN adjustment_deduction BIGINT  NOT NULL DEFAULT 0,   -- POST_TAX DEDUCTION adjustments (reduces net, floor-protected)
    ADD COLUMN adjustment_capped    BOOLEAN NOT NULL DEFAULT FALSE; -- true when post-tax adjustments were reduced to protect the net floor
