-- V60__public_careers_portal.sql
--
-- Public Careers Portal: expose each tenant's PUBLISHED job postings on an
-- unauthenticated, per-tenant careers page (/careers/{slug}) and let external
-- candidates apply directly. The internal ATS (job_postings / job_applications,
-- both RLS-scoped) already exists; this migration adds the pieces the *public*,
-- session-less path needs.
--
-- Three changes:
--   1. tenants.careers_slug / careers_enabled / careers_intro — the public URL
--      handle, an on/off switch, and an optional tagline for the landing page.
--      The slug is backfilled here for every existing tenant and generated at
--      registration for new ones (see platform.TenantUserWrites).
--   2. job_application_resumes — resume bytes stored in-row as BYTEA, out of line
--      from job_applications so the applications list never drags files across the
--      wire (same trade-off as the V34 document vault). Standard tenant_isolation
--      RLS; maestro_app receives DML via V24's ALTER DEFAULT PRIVILEGES.
--   3. RECRUITMENT platform flag — global kill switch for the careers portal,
--      mirroring DOCUMENT_VAULT (V34) / LOAN_MANAGEMENT (V30).

-- =====================================================================
-- 1. tenants: public careers columns
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS careers_slug    VARCHAR(80),
    ADD COLUMN IF NOT EXISTS careers_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS careers_intro   VARCHAR(500);

-- Backfill a slug for every existing tenant: slugified company name plus a short
-- id fragment, which guarantees uniqueness even when two companies share a name.
-- COALESCE/NULLIF guards the degenerate case of an all-symbol company name.
UPDATE tenants
SET careers_slug =
        COALESCE(NULLIF(trim(BOTH '-' FROM regexp_replace(lower(company_name), '[^a-z0-9]+', '-', 'g')), ''), 'company')
        || '-' || substr(replace(id::text, '-', ''), 1, 6)
WHERE careers_slug IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_careers_slug
    ON tenants (careers_slug)
    WHERE careers_slug IS NOT NULL;

-- =====================================================================
-- 2. job_application_resumes
-- =====================================================================

CREATE TABLE IF NOT EXISTS job_application_resumes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100),
    size_bytes     BIGINT NOT NULL,
    data           BYTEA NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One resume per application; replaces the disk-based storage the old
    -- RecruitmentService.saveResume used.
    CONSTRAINT uq_resume_application UNIQUE (application_id)
);

CREATE INDEX IF NOT EXISTS idx_resume_application
    ON job_application_resumes (tenant_id, application_id);

ALTER TABLE job_application_resumes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_application_resumes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- =====================================================================
-- 3. RECRUITMENT platform flag (global kill switch; absent row = enabled)
-- =====================================================================

INSERT INTO platform_flags (name, enabled, description, updated_by)
VALUES ('RECRUITMENT', true, 'Public careers portal + applicant tracking', 'system')
ON CONFLICT (name) DO NOTHING;
