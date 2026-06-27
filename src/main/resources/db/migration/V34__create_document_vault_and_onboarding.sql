-- V34__create_document_vault_and_onboarding.sql
--
-- Two linked HR features in one migration:
--
--   1. Employee Document Vault. Employees upload certificates, contracts and ID
--      documents; HR views/downloads them; a daily job alerts HR about documents
--      nearing expiry. Files are stored in-row as BYTEA (no external object store) —
--      the same trade-off the rest of the app already accepts for small artifacts.
--      Blobs are read lazily (see EmployeeDocument#fileData) so the per-employee
--      list never drags every file across the wire.
--
--   2. Onboarding Checklist. When an employee is created with status=ONBOARDING, a
--      fixed set of starter tasks is seeded (in Java — see OnboardingService) and the
--      employee checks them off from their dashboard. The default task list lives in
--      code, not here, because the rows are per-employee and cannot exist before the
--      employee does.
--
-- RLS: both tables are tenant-scoped and get the standard V20/V25 NULLIF
-- tenant_isolation policy (USING + WITH CHECK). The maestro_app (NOBYPASSRLS) role
-- receives DML on both via V24's ALTER DEFAULT PRIVILEGES.
--
-- Feature flag: DOCUMENT_VAULT is seeded into platform_flags (enabled), mirroring
-- LOAN_MANAGEMENT (V30). The per-tenant plan gate lives in SubscriptionPlan.

-- =====================================================================
-- 1. employee_documents
-- =====================================================================

CREATE TABLE employee_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    employee_id     UUID NOT NULL REFERENCES employees(id),
    document_type   VARCHAR(20) NOT NULL,            -- PASSPORT/NIN/BVN/CERTIFICATE/CONTRACT/WORK_PERMIT/OTHER
    file_name       VARCHAR(255) NOT NULL,
    file_data       BYTEA NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    mime_type       VARCHAR(100),
    expiry_date     DATE,                            -- nullable: not every document expires
    uploaded_by     VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_employee_documents_type CHECK (
        document_type IN ('PASSPORT','NIN','BVN','CERTIFICATE','CONTRACT','WORK_PERMIT','OTHER')
    )
);

-- Per-employee document list (the common read).
CREATE INDEX idx_employee_documents_employee ON employee_documents (tenant_id, employee_id);
-- Expiry sweep: only rows that actually carry an expiry date are of interest.
CREATE INDEX idx_employee_documents_expiry
    ON employee_documents (expiry_date)
    WHERE expiry_date IS NOT NULL;

ALTER TABLE employee_documents ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_documents
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 2. employee_onboarding_tasks
-- =====================================================================

CREATE TABLE employee_onboarding_tasks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    employee_id   UUID NOT NULL REFERENCES employees(id),
    task_name     VARCHAR(255) NOT NULL,
    task_order    INT NOT NULL,
    is_completed  BOOLEAN NOT NULL DEFAULT false,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ordered per-employee checklist read.
CREATE INDEX idx_onboarding_tasks_employee
    ON employee_onboarding_tasks (tenant_id, employee_id, task_order);

ALTER TABLE employee_onboarding_tasks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_onboarding_tasks
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 3. DOCUMENT_VAULT platform flag (global kill switch; absent row = enabled)
-- =====================================================================

INSERT INTO platform_flags (name, enabled, description, updated_by)
VALUES ('DOCUMENT_VAULT', true, 'Employee document vault (certificates, contracts, IDs with expiry alerts)', 'system')
ON CONFLICT (name) DO NOTHING;
