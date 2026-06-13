-- V25__normalize_rls_policies.sql
--
-- Phase E4a: normalize every tenant-isolation RLS policy to the NULLIF form.
--
-- Background: the original per-command policies (V2-V7, V10) compare
--     tenant_id = current_setting('app.current_tenant', true)::uuid
-- with no NULLIF. When no tenant session is bound, DataSourceConfig writes the
-- empty-string sentinel '' into app.current_tenant, and ''::uuid raises
-- "invalid input syntax for type uuid". Today the app connects as `postgres`
-- (BYPASSRLS) so these policies never execute; the moment the runtime flips to
-- the NOBYPASSRLS `maestro_app` role (a later phase) they would start throwing.
-- The correct form, already used by V20/V22, is:
--     tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
-- which maps the empty sentinel to NULL so the comparison returns no rows.
--
-- Two classes of fix:
--   1. tenants, users, subscription_audit had ONLY the broken policies (V20 never
--      re-did them) -> drop the per-command policies and add one NULLIF policy.
--   2. departments, pay_grades, employees, payroll_runs, payroll_entries,
--      leave_types, leave_requests, leave_balances, attendance_records already
--      received a correct NULLIF `tenant_isolation` policy in V20, but kept their
--      broken per-command policies from V3-V7. Permissive policies are OR-combined,
--      so the broken branch still errored on the empty sentinel -> drop the
--      leftover per-command policies; the good `tenant_isolation` stays.
--
-- Idempotent: DROP ... IF EXISTS, CREATE guarded on pg_policies. FORCE ROW LEVEL
-- SECURITY flags are intentionally left untouched (irrelevant to the non-owner
-- maestro_app role).

-- =====================================================================
-- 1. Tables that had only the broken per-command policies
-- =====================================================================

-- ── tenants (keyed on id, not tenant_id) ─────────────────────────────
DROP POLICY IF EXISTS tenant_isolation_select ON tenants;
DROP POLICY IF EXISTS tenant_isolation_insert ON tenants;
DROP POLICY IF EXISTS tenant_isolation_update ON tenants;
DROP POLICY IF EXISTS tenant_isolation_delete ON tenants;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname='public' AND tablename='tenants'
                     AND policyname='tenant_isolation') THEN
        CREATE POLICY tenant_isolation ON tenants
            USING (id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
END $$;

-- ── users (keyed on tenant_id) ───────────────────────────────────────
DROP POLICY IF EXISTS users_tenant_isolation_select ON users;
DROP POLICY IF EXISTS users_tenant_isolation_insert ON users;
DROP POLICY IF EXISTS users_tenant_isolation_update ON users;
DROP POLICY IF EXISTS users_tenant_isolation_delete ON users;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname='public' AND tablename='users'
                     AND policyname='tenant_isolation') THEN
        CREATE POLICY tenant_isolation ON users
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
END $$;

-- ── subscription_audit (keyed on tenant_id) ──────────────────────────
DROP POLICY IF EXISTS subscription_audit_tenant_isolation ON subscription_audit;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname='public' AND tablename='subscription_audit'
                     AND policyname='tenant_isolation') THEN
        CREATE POLICY tenant_isolation ON subscription_audit
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
END $$;

-- =====================================================================
-- 2. Drop the leftover per-command policies from V3-V7. Each of these
--    tables already has a correct NULLIF `tenant_isolation` policy (V20).
-- =====================================================================

DROP POLICY IF EXISTS departments_tenant_isolation_select ON departments;
DROP POLICY IF EXISTS departments_tenant_isolation_insert ON departments;
DROP POLICY IF EXISTS departments_tenant_isolation_update ON departments;
DROP POLICY IF EXISTS departments_tenant_isolation_delete ON departments;

DROP POLICY IF EXISTS pay_grades_tenant_isolation_select ON pay_grades;
DROP POLICY IF EXISTS pay_grades_tenant_isolation_insert ON pay_grades;
DROP POLICY IF EXISTS pay_grades_tenant_isolation_update ON pay_grades;
DROP POLICY IF EXISTS pay_grades_tenant_isolation_delete ON pay_grades;

DROP POLICY IF EXISTS employees_tenant_isolation_select ON employees;
DROP POLICY IF EXISTS employees_tenant_isolation_insert ON employees;
DROP POLICY IF EXISTS employees_tenant_isolation_update ON employees;
DROP POLICY IF EXISTS employees_tenant_isolation_delete ON employees;

DROP POLICY IF EXISTS payroll_runs_tenant_isolation_select ON payroll_runs;
DROP POLICY IF EXISTS payroll_runs_tenant_isolation_insert ON payroll_runs;
DROP POLICY IF EXISTS payroll_runs_tenant_isolation_update ON payroll_runs;
DROP POLICY IF EXISTS payroll_runs_tenant_isolation_delete ON payroll_runs;

DROP POLICY IF EXISTS payroll_entries_tenant_isolation_select ON payroll_entries;
DROP POLICY IF EXISTS payroll_entries_tenant_isolation_insert ON payroll_entries;
DROP POLICY IF EXISTS payroll_entries_tenant_isolation_update ON payroll_entries;
DROP POLICY IF EXISTS payroll_entries_tenant_isolation_delete ON payroll_entries;

DROP POLICY IF EXISTS leave_types_tenant_isolation_select ON leave_types;
DROP POLICY IF EXISTS leave_types_tenant_isolation_insert ON leave_types;
DROP POLICY IF EXISTS leave_types_tenant_isolation_update ON leave_types;
DROP POLICY IF EXISTS leave_types_tenant_isolation_delete ON leave_types;

DROP POLICY IF EXISTS leave_requests_tenant_isolation_select ON leave_requests;
DROP POLICY IF EXISTS leave_requests_tenant_isolation_insert ON leave_requests;
DROP POLICY IF EXISTS leave_requests_tenant_isolation_update ON leave_requests;
DROP POLICY IF EXISTS leave_requests_tenant_isolation_delete ON leave_requests;

DROP POLICY IF EXISTS leave_balances_tenant_isolation_select ON leave_balances;
DROP POLICY IF EXISTS leave_balances_tenant_isolation_insert ON leave_balances;
DROP POLICY IF EXISTS leave_balances_tenant_isolation_update ON leave_balances;
DROP POLICY IF EXISTS leave_balances_tenant_isolation_delete ON leave_balances;

DROP POLICY IF EXISTS attendance_records_tenant_isolation_select ON attendance_records;
DROP POLICY IF EXISTS attendance_records_tenant_isolation_insert ON attendance_records;
DROP POLICY IF EXISTS attendance_records_tenant_isolation_update ON attendance_records;
DROP POLICY IF EXISTS attendance_records_tenant_isolation_delete ON attendance_records;
