-- V24__create_app_role.sql
--
-- Phase E3: introduce a dedicated, least-privilege application role so that
-- PostgreSQL Row Level Security can actually be ENFORCED at the SQL level.
--
-- Background: the app currently connects as `postgres` — a superuser and the
-- owner of every table — for which PostgreSQL unconditionally BYPASSES all RLS
-- policies. That makes the policies defined in V2–V22 inert at runtime; tenant
-- isolation today rests solely on Hibernate's @SQLRestriction. This role is a
-- NON-owner with NOBYPASSRLS, so RLS policies apply to it normally. Because it
-- never owns objects, RLS applies without needing FORCE ROW LEVEL SECURITY.
--
-- Scope note (Phase E3): this migration only CREATES the role and grants. The
-- app's runtime datasource is intentionally NOT switched to it yet — doing so
-- would break login / registration / webhook / subscription-lapse flows, which
-- depend on cross-tenant or pre-tenant reads that RLS would block (e.g. login
-- looks up `users` by email before any tenant is bound, and the `users` entity
-- has no @SQLRestriction). Flipping the runtime datasource is deferred to a
-- later phase together with the required auth/bootstrap refactor.
--
-- For now the role exists so RawConnectionRlsIsolationTest can prove, via raw
-- JDBC, that RLS genuinely isolates tenants for a NOBYPASSRLS role.
--
-- Flyway keeps running as the owner (`postgres`), so future migrations retain
-- their DDL rights; maestro_app gets DML only and owns nothing.
--
-- The password is injected via the Flyway placeholder ${app_db_password}, wired
-- from APP_DB_PASSWORD in application.yml.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'maestro_app') THEN
        CREATE ROLE maestro_app
            LOGIN
            PASSWORD '${app_db_password}'
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOBYPASSRLS;
    END IF;
END
$$;

-- Keep the role's attributes/password in sync even if it pre-existed (roles are
-- cluster-global, so it may have been created by a sibling database).
ALTER ROLE maestro_app WITH LOGIN PASSWORD '${app_db_password}' NOSUPERUSER NOBYPASSRLS;

-- Schema + DML privileges only. No ownership, no DDL.
GRANT USAGE ON SCHEMA public TO maestro_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO maestro_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO maestro_app;

-- Apply the same grants automatically to tables/sequences created by future
-- migrations (which also run as the owner, `postgres`).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO maestro_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO maestro_app;

-- The application role has no business reading or writing Flyway's bookkeeping.
REVOKE ALL ON flyway_schema_history FROM maestro_app;
