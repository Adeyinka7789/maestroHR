-- V51: Retirement module (Stage 1) — schema only.
--
-- retirement_policies: single settings row per tenant (UNIQUE tenant_id), holding the
-- retirement age and comma-separated notification thresholds used to compute an
-- employee's estimated retirement date. Standalone module, read-only display in this stage.

CREATE TABLE retirement_policies (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                    UUID NOT NULL REFERENCES tenants(id),
    retirement_age               INTEGER NOT NULL DEFAULT 60,
    notification_threshold_days  VARCHAR(200) NOT NULL DEFAULT '180,30',
    created_at                   TIMESTAMPTZ DEFAULT now(),
    updated_at                   TIMESTAMPTZ DEFAULT now(),
    deleted_at                   TIMESTAMPTZ,
    CONSTRAINT uq_retirement_policies_tenant UNIQUE (tenant_id)
);

CREATE INDEX idx_retirement_policies_tenant ON retirement_policies (tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE retirement_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON retirement_policies
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
