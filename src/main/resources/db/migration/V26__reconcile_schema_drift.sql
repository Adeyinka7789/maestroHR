-- V26__reconcile_schema_drift.sql
--
-- Phase E4a: align the migration-defined schema with the JPA entity mappings so
-- the application can run with hibernate.ddl-auto=validate instead of `update`.
-- Until now `update` silently reconciled the gaps below at every boot; with
-- `validate` the schema must already match, so we declare the gaps explicitly.
--
-- Every statement is idempotent (IF NOT EXISTS / type-converging ALTER guarded on
-- the current type), so this is a no-op on the existing database (where `update`
-- already created these objects) and corrects any environment whose schema was
-- built purely from migrations.
--
-- KNOWN LIMITATION (deliberately not fixed here): a brand-new database still
-- cannot be built from scratch because V19's INSERT references
-- clearance_items.updated_at, a column V18 never created. Repairing that requires
-- editing the already-applied V18/V19 (a checksum change + `flyway repair`) and
-- was deferred. The clearance_items.updated_at ADD below makes EXISTING databases
-- validate-clean; it does not make V19 succeed on an empty database.

-- =====================================================================
-- 1. Columns required by BaseEntity (created_at / updated_at) that the
--    original CREATE TABLE statements omitted.
-- =====================================================================
ALTER TABLE payroll_entries  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE review_questions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE review_sections  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE clearance_items  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE job_applications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- =====================================================================
-- 2. Type mismatches: the entity fields are String, but the columns were
--    created as uuid. Hibernate validate rejects uuid (Types#OTHER) where it
--    expects varchar (Types#VARCHAR). Converge to varchar to match the mapping.
--    Guarded so this only rewrites the column when it is still uuid.
-- =====================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema='public' AND table_name='departments'
                 AND column_name='head_employee_id' AND data_type='uuid') THEN
        ALTER TABLE departments
            ALTER COLUMN head_employee_id TYPE varchar(255) USING head_employee_id::text;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema='public' AND table_name='job_applications'
                 AND column_name='converted_to_employee_id' AND data_type='uuid') THEN
        ALTER TABLE job_applications
            ALTER COLUMN converted_to_employee_id TYPE varchar(255) USING converted_to_employee_id::text;
    END IF;
END $$;

-- =====================================================================
-- 3. Foreign keys that V13-V19 never declared (their columns were bare
--    `UUID NOT NULL`). Hibernate's `update` had been auto-creating these with
--    generated names (fk<hash>). Declare them explicitly with deterministic
--    names. Guarded: skip when any FK already covers the column, so this neither
--    duplicates the Hibernate-created constraints on existing databases nor fails.
-- =====================================================================
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT * FROM (VALUES
            ('certifications',     'employee_id',       'employees',         'id'),
            ('certifications',     'tenant_id',         'tenants',           'id'),
            ('clearance_items',    'tenant_id',         'tenants',           'id'),
            ('employee_clearance', 'clearance_item_id', 'clearance_items',   'id'),
            ('employee_clearance', 'exit_request_id',   'exit_requests',     'id'),
            ('employee_clearance', 'tenant_id',         'tenants',           'id'),
            ('employee_trainings', 'employee_id',       'employees',         'id'),
            ('employee_trainings', 'tenant_id',         'tenants',           'id'),
            ('exit_requests',      'employee_id',       'employees',         'id'),
            ('exit_requests',      'tenant_id',         'tenants',           'id'),
            ('final_settlements',  'exit_request_id',   'exit_requests',     'id'),
            ('final_settlements',  'tenant_id',         'tenants',           'id'),
            ('job_applications',   'job_posting_id',    'job_postings',      'id'),
            ('job_applications',   'tenant_id',         'tenants',           'id'),
            ('job_postings',       'tenant_id',         'tenants',           'id'),
            ('review_cycles',      'employee_id',       'employees',         'id'),
            ('review_cycles',      'reviewer_id',       'employees',         'id'),
            ('review_cycles',      'tenant_id',         'tenants',           'id'),
            ('review_templates',   'tenant_id',         'tenants',           'id'),
            ('training_programs',  'tenant_id',         'tenants',           'id')
        ) AS v(child, col, parent, pcol)
    )
    LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint con
            JOIN pg_class rel       ON rel.oid = con.conrelid
            JOIN pg_attribute att   ON att.attrelid = con.conrelid
                                   AND att.attnum = ANY (con.conkey)
            WHERE con.contype = 'f'
              AND rel.relname = r.child
              AND att.attname = r.col
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(%I)',
                r.child, r.child || '_' || r.col || '_fkey', r.col, r.parent, r.pcol
            );
        END IF;
    END LOOP;
END $$;
