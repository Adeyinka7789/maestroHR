-- V28__create_broadcasts.sql
--
-- Superadmin Feature 5: platform-wide broadcasts. A SUPER_ADMIN composes an
-- announcement targeted at ALL tenants or specific plan tiers; tenant users see
-- unread broadcasts as a dismissible banner and dismiss = mark-as-read.
--
-- RLS note: both tables are intentionally PLATFORM-GLOBAL — no tenant_id, no
-- ENABLE ROW LEVEL SECURITY, no tenant_isolation policy. broadcasts are authored
-- by the platform and read across every tenant; broadcast_reads tracks per-user
-- read state keyed by email and likewise spans tenants. Because no policy is
-- attached, the primary maestro_app (NOBYPASSRLS) datasource reads/writes these
-- freely — V24's ALTER DEFAULT PRIVILEGES already grants it DML on tables created
-- by later migrations, so no privileged datasource is required here.

CREATE TABLE broadcasts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title        VARCHAR(255) NOT NULL,
    body         TEXT NOT NULL,
    target_plans TEXT NOT NULL DEFAULT 'ALL',
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE broadcast_reads (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id UUID NOT NULL REFERENCES broadcasts(id) ON DELETE CASCADE,
    user_email   VARCHAR(255) NOT NULL,
    read_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (broadcast_id, user_email)
);

-- Lookup paths: newest-first broadcast listing, and the per-user "have I read it?"
-- check driven by the unread feed and the mark-as-read insert.
CREATE INDEX idx_broadcasts_created_at ON broadcasts (created_at DESC);
CREATE INDEX idx_broadcast_reads_user_email ON broadcast_reads (user_email);
