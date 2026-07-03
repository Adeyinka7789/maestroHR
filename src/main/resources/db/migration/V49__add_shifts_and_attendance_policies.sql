-- V49: Shifts + attendance policies — schema only (Stage 1 of the attendance-deduction feature).
--
-- Changes:
--   1. shifts               — tenant-scoped named shift (start/end time) table with RLS
--   2. attendance_policies  — tenant-scoped late/absence deduction rules table with RLS
--   3. employees             — optional FK shift_id (nullable → no shift assigned yet)
--   4. pay_grades            — optional FK attendance_policy_id (nullable → falls back to tenant default)
--
-- No resolution logic, no AttendanceService/PayrollEngine wiring yet — that's Stage 2+.

-- ─── 1. shifts ─────────────────────────────────────────────────────────────────
CREATE TABLE shifts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    name        VARCHAR(100) NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_shifts_tenant ON shifts (tenant_id, is_default) WHERE deleted_at IS NULL;

ALTER TABLE shifts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shifts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ─── 2. attendance_policies ──────────────────────────────────────────────────
CREATE TABLE attendance_policies (
    id                                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                           UUID NOT NULL REFERENCES tenants(id),
    name                                VARCHAR(100) NOT NULL,
    description                         VARCHAR(500),
    grace_period_minutes                INTEGER NOT NULL DEFAULT 0,
    late_deduction_type                 VARCHAR(20) NOT NULL DEFAULT 'FLAT',
    late_deduction_value                NUMERIC(12,2) NOT NULL DEFAULT 0,
    late_free_count                     INTEGER NOT NULL DEFAULT 0,
    absence_deduction_type              VARCHAR(20) NOT NULL DEFAULT 'FLAT',
    absence_deduction_value             NUMERIC(12,2) NOT NULL DEFAULT 0,
    late_to_absence_conversion_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    late_to_absence_conversion_count    INTEGER,
    is_active                           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                          TIMESTAMPTZ DEFAULT now(),
    updated_at                          TIMESTAMPTZ DEFAULT now(),
    deleted_at                          TIMESTAMPTZ
);

CREATE INDEX idx_attendance_policies_tenant ON attendance_policies (tenant_id, is_active) WHERE deleted_at IS NULL;

ALTER TABLE attendance_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attendance_policies
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ─── 3. employees: optional shift link ────────────────────────────────────────
ALTER TABLE employees ADD COLUMN shift_id UUID REFERENCES shifts(id);

-- ─── 4. pay_grades: optional attendance policy link ───────────────────────────
ALTER TABLE pay_grades ADD COLUMN attendance_policy_id UUID REFERENCES attendance_policies(id);
