-- V66__public_holidays.sql
--
-- Per-tenant public-holiday calendar. Deliberately tenant-scoped (not a shared national list)
-- because not every employer observes every public holiday, and shift-based businesses often work
-- them — so the tenant admin decides which dates count as holidays for overtime. A date listed and
-- active here makes all hours worked that day bill at the overtime policy's holiday multiplier
-- (see OvertimeService); otherwise the day falls back to weekday/weekend rules.

CREATE TABLE public_holidays (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    holiday_date DATE NOT NULL,
    name         VARCHAR(120) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_public_holiday UNIQUE (tenant_id, holiday_date)
);

CREATE INDEX idx_public_holidays_scan
    ON public_holidays (tenant_id, holiday_date)
    WHERE is_active;

ALTER TABLE public_holidays ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public_holidays
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
