-- V64__overtime.sql
--
-- Overtime & Shift Allowance calculator. Bridges the Attendance module (clock-in hours) to
-- payroll: HR computes overtime for a period from attendance, reviews/approves it, and each
-- approved entry emits a PENDING payroll_adjustment of the seeded OVERTIME type — so the next
-- payroll run for that period consumes it exactly like any other adjustment (V61 plumbing).
--
-- Two tables, both tenant-scoped with the standard V20/V25 NULLIF RLS policy; maestro_app gets
-- DML via V24's ALTER DEFAULT PRIVILEGES.

-- =====================================================================
-- 1. overtime_policies  (one active policy per tenant — the rate card)
-- =====================================================================

CREATE TABLE overtime_policies (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                   VARCHAR(100) NOT NULL DEFAULT 'Default Overtime Policy',
    -- Hours in a normal working day; anything beyond this on a weekday is overtime.
    standard_daily_hours   DECIMAL(5,2) NOT NULL DEFAULT 8.00,
    -- Divisor that turns a monthly gross (kobo) into an hourly rate: gross / standard_monthly_hours.
    standard_monthly_hours INT NOT NULL DEFAULT 173,
    weekday_multiplier     DECIMAL(4,2) NOT NULL DEFAULT 1.50,   -- normal overtime
    weekend_multiplier     DECIMAL(4,2) NOT NULL DEFAULT 2.00,   -- Sat/Sun duty
    holiday_multiplier     DECIMAL(4,2) NOT NULL DEFAULT 2.00,   -- reserved; holiday calendar is future work
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT chk_overtime_daily_hours CHECK (standard_daily_hours > 0 AND standard_daily_hours <= 24),
    CONSTRAINT chk_overtime_monthly_hours CHECK (standard_monthly_hours > 0),
    CONSTRAINT chk_overtime_multipliers CHECK (weekday_multiplier >= 1 AND weekend_multiplier >= 1 AND holiday_multiplier >= 1)
);

-- One active (non-deleted) policy per tenant.
CREATE UNIQUE INDEX uq_overtime_policy_active
    ON overtime_policies (tenant_id)
    WHERE deleted_at IS NULL;

ALTER TABLE overtime_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON overtime_policies
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 2. overtime_entries  (computed per employee, per period)
-- =====================================================================

CREATE TABLE overtime_entries (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    employee_id           UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    period_month          INT NOT NULL,
    period_year           INT NOT NULL,
    weekday_ot_hours      DECIMAL(7,2) NOT NULL DEFAULT 0,
    weekend_ot_hours      DECIMAL(7,2) NOT NULL DEFAULT 0,
    holiday_ot_hours      DECIMAL(7,2) NOT NULL DEFAULT 0,
    hourly_rate_kobo      BIGINT NOT NULL DEFAULT 0,
    amount_kobo           BIGINT NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'DRAFT',   -- DRAFT | APPROVED | REJECTED
    -- Set when APPROVED: the payroll_adjustment this overtime emitted (so reject can cancel it).
    payroll_adjustment_id UUID REFERENCES payroll_adjustments(id) ON DELETE SET NULL,
    computed_at           TIMESTAMPTZ,
    approved_by           VARCHAR(255),
    approved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_overtime_entry_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT chk_overtime_entry_status CHECK (status IN ('DRAFT','APPROVED','REJECTED')),
    -- Recompute upserts by (employee, period), so at most one entry per employee per period.
    CONSTRAINT uq_overtime_entry UNIQUE (tenant_id, employee_id, period_year, period_month)
);

CREATE INDEX idx_overtime_entries_period
    ON overtime_entries (tenant_id, period_year, period_month, status);

ALTER TABLE overtime_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON overtime_entries
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
