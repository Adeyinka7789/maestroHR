-- V29__add_soft_delete.sql
--
-- Enterprise soft-delete for departments, employees and pay_grades. A nullable
-- deleted_at marks a row as trashed (90-day retention before a scheduled job purges
-- it). The three entities' @SQLRestriction gains "AND deleted_at IS NULL", so the
-- application's scoped (maestro_app) reads hide trashed rows, while the privileged
-- (postgres) datasource — the cleanup job and the super-admin trash page — still
-- sees them. The V20/V25 RLS policies are intentionally left unchanged: they filter
-- by tenant_id only, which is exactly what the privileged paths rely on.
--
-- The existing tenant-scoped UNIQUE constraints are rewritten as PARTIAL unique
-- indexes scoped to live rows (WHERE deleted_at IS NULL) so a trashed row no longer
-- reserves its name / number / email — a replacement carrying the same value can be
-- created while the trashed original awaits its purge. (The app's existsByName...
-- checks already ignore trashed rows via the new @SQLRestriction; without this the
-- DB constraint would still reject the insert.)
--
-- Every statement is idempotent (IF NOT EXISTS / introspective drop), matching the
-- V26 reconcile style, so this is a no-op where the objects already exist.

-- 1. The soft-delete marker column on each table.
ALTER TABLE departments ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE employees   ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE pay_grades  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- 2. Partial indexes over trashed rows only — back the daily cleanup scan
--    (deleted_at < now() - 90 days) and the super-admin trash listing.
CREATE INDEX IF NOT EXISTS idx_departments_deleted_at ON departments (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_employees_deleted_at   ON employees   (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pay_grades_deleted_at  ON pay_grades  (deleted_at) WHERE deleted_at IS NOT NULL;

-- 3. Rewrite the tenant-scoped uniqueness as partial unique indexes over live rows.
--    The original constraints are dropped by introspection (departments/pay_grades
--    were declared anonymously, so their generated names are not relied upon).
DO $$
DECLARE
    r        RECORD;
    con_name text;
BEGIN
    FOR r IN (
        SELECT * FROM (VALUES
            ('departments', ARRAY['tenant_id','name'],            'uk_departments_name_per_tenant_live'),
            ('pay_grades',  ARRAY['tenant_id','name'],            'uk_pay_grades_name_per_tenant_live'),
            ('employees',   ARRAY['tenant_id','employee_number'], 'uk_employee_number_per_tenant_live'),
            ('employees',   ARRAY['tenant_id','email'],           'uk_employee_email_per_tenant_live')
        ) AS v(tbl, cols, idx_name)
    )
    LOOP
        -- Find the existing UNIQUE constraint whose column set is exactly r.cols.
        SELECT con.conname INTO con_name
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE con.contype = 'u'
          AND rel.relname = r.tbl
          AND (
              SELECT array_agg(att.attname::text ORDER BY att.attname::text)
              FROM pg_attribute att
              WHERE att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
          ) = (SELECT array_agg(c ORDER BY c) FROM unnest(r.cols) AS c)
        LIMIT 1;

        IF con_name IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, con_name);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = r.idx_name) THEN
            EXECUTE format('CREATE UNIQUE INDEX %I ON %I (%s) WHERE deleted_at IS NULL',
                           r.idx_name, r.tbl, array_to_string(r.cols, ', '));
        END IF;
    END LOOP;
END $$;
