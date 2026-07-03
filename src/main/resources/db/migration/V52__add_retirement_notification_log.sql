-- V52: Retirement module (Stage 2) — notification log, schema only.
--
-- retirement_notification_logs: one row per (employee, threshold_days) pair that HR has
-- already been notified about, so RetirementNotificationJob never re-fires the same
-- threshold on a later daily run. UNIQUE constraint makes this idempotent even under a
-- race/double-run of the job.

CREATE TABLE retirement_notification_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    employee_id     UUID NOT NULL REFERENCES employees(id),
    threshold_days  INTEGER NOT NULL,
    notified_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_retirement_notification_logs_employee_threshold UNIQUE (employee_id, threshold_days)
);

CREATE INDEX idx_retirement_notification_logs_tenant ON retirement_notification_logs (tenant_id);

ALTER TABLE retirement_notification_logs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON retirement_notification_logs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
