-- Account / company self-service deletion (soft-delete → 90-day retention → purge).
--
-- 1) tenants.deleted_at — the soft-delete marker. A non-null value means the company has been
--    trashed by its owner: it is deactivated (is_active=false) and hidden from login/switcher
--    (see AuthBootstrapQueries.findAllUsersByEmail), shows on the super-admin trash page for a
--    90-day grace period, then SoftDeleteCleanupJob purges it. Mirrors the deleted_at pattern
--    already used by employees / departments / pay_grades.
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- 2) Make every single-column foreign key that references tenants ON DELETE CASCADE, so the
--    eventual hard purge (DELETE FROM tenants WHERE id = ?) tears down all of a company's data in
--    one statement instead of failing on the ~20 tenant-scoped tables that were created without a
--    cascade rule. Done generically (rather than table-by-table) so it covers every current
--    tenant-scoped table and is order-independent; already-cascading FKs (confdeltype 'c') are
--    skipped. New tenant-scoped tables should be created with ON DELETE CASCADE going forward.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT con.conname, cl.relname AS tbl, att.attname AS col
        FROM pg_constraint con
        JOIN pg_class cl  ON cl.oid  = con.conrelid
        JOIN pg_class ref ON ref.oid = con.confrelid
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
        WHERE con.contype = 'f'
          AND ref.relname = 'tenants'
          AND con.confdeltype <> 'c'            -- 'c' = ON DELETE CASCADE already
          AND array_length(con.conkey, 1) = 1   -- single-column FK only
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.conname);
        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES tenants(id) ON DELETE CASCADE',
            r.tbl, r.conname, r.col);
    END LOOP;
END $$;

-- Index the retention scan (WHERE deleted_at IS NOT NULL) used by the trash page and purge job.
CREATE INDEX IF NOT EXISTS idx_tenants_deleted_at ON tenants (deleted_at) WHERE deleted_at IS NOT NULL;
