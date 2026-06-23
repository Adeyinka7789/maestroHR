-- V32__device_sync.sql
--
-- Hardware attendance sync: ZKTeco / biometric device integration.
--
-- Four concerns in one migration:
--
--   1. device_api_keys  — one row per physical device. The raw API key is never
--      stored; only its BCrypt hash (api_key_hash). The filter hashes the incoming
--      X-Device-Api-Key header and compares against this column.
--
--   2. device_employee_enrollments — maps the short numeric/alphanumeric ID
--      that the physical device stores for each employee ("device employee ID")
--      to the MaestroHR employee UUID. HR admin sets this up once per device-
--      employee pair via the Attendance → Device Management UI.
--
--   3. device_sync_errors — append-only log of events that the sync service
--      could not process (unknown enrollment, unknown API key, bad payload).
--      HR sees this in the Device Management page and fixes enrollments.
--
--   4. attendance_records.check_in_method — existing VARCHAR(20) column gains
--      a CHECK constraint documenting the three valid values: MANUAL, SELF_SERVICE,
--      BIOMETRIC. No data migration needed (existing rows are all MANUAL or
--      SELF_SERVICE already).
--
-- RLS: device_api_keys, device_employee_enrollments, and device_sync_errors
-- are all tenant-scoped and get the standard NULLIF tenant_isolation policy.
-- DeviceAuthFilter is responsible for setting app.current_tenant before any
-- query touches these tables.

-- =====================================================================
-- 1. device_api_keys
-- =====================================================================

CREATE TABLE device_api_keys (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id),
    device_name       VARCHAR(100) NOT NULL,
    device_identifier VARCHAR(100),              -- device's own serial / machine number (informational)
    location          VARCHAR(200),
    api_key_hash      VARCHAR(64) NOT NULL,        -- SHA-256 hex of the raw key (deterministic: enables single-query lookup before TenantContext is set)
    is_active         BOOLEAN NOT NULL DEFAULT true,
    last_used_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_api_keys_tenant ON device_api_keys (tenant_id, is_active);

ALTER TABLE device_api_keys ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_api_keys
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 2. device_employee_enrollments
-- =====================================================================

CREATE TABLE device_employee_enrollments (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id),
    device_api_key_id    UUID NOT NULL REFERENCES device_api_keys(id) ON DELETE CASCADE,
    device_employee_id   VARCHAR(100) NOT NULL,   -- short ID stored inside the physical device
    employee_id          UUID NOT NULL REFERENCES employees(id),
    enrolled_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One enrollment per device-employee pair prevents duplicate mappings.
    CONSTRAINT uk_enrollment_device_emp UNIQUE (device_api_key_id, device_employee_id)
);

CREATE INDEX idx_enrollments_device ON device_employee_enrollments (device_api_key_id, device_employee_id);
CREATE INDEX idx_enrollments_employee ON device_employee_enrollments (tenant_id, employee_id);

ALTER TABLE device_employee_enrollments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_employee_enrollments
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 3. device_sync_errors — append-only, HR-visible error log
-- =====================================================================

CREATE TABLE device_sync_errors (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL REFERENCES tenants(id),
    device_api_key_id        UUID REFERENCES device_api_keys(id) ON DELETE SET NULL,  -- null when the key itself was unknown
    device_employee_id       VARCHAR(100),          -- identifier the device sent (may be null for payload errors)
    device_identifier        VARCHAR(100),          -- deviceId field from payload
    event_type               VARCHAR(20),           -- CHECK_IN / CHECK_OUT
    event_timestamp          TIMESTAMPTZ,           -- timestamp the device reported
    error_reason             VARCHAR(500) NOT NULL,
    raw_payload              TEXT NOT NULL,
    resolved                 BOOLEAN NOT NULL DEFAULT false,
    resolved_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sync_errors_tenant_unresolved ON device_sync_errors (tenant_id, resolved, created_at DESC);
CREATE INDEX idx_sync_errors_device_key        ON device_sync_errors (device_api_key_id);

ALTER TABLE device_sync_errors ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_sync_errors
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 4. attendance_records.check_in_method — add BIOMETRIC as valid value
-- =====================================================================

ALTER TABLE attendance_records
    ADD CONSTRAINT ck_check_in_method
    CHECK (check_in_method IN ('MANUAL', 'SELF_SERVICE', 'BIOMETRIC'));

-- =====================================================================
-- 5. platform_flags — seed HARDWARE_SYNC
-- =====================================================================

INSERT INTO platform_flags (name, enabled, description, updated_by)
VALUES ('HARDWARE_SYNC', true, 'Hardware biometric device attendance sync (ZKTeco / fingerprint readers)', 'system')
ON CONFLICT (name) DO NOTHING;
