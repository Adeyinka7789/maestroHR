-- Promote the earliest-created user per tenant to SYSTEM_ADMIN (tenant owner).
-- New registrations already get SYSTEM_ADMIN; this backfills existing tenants.
UPDATE users
SET role = 'SYSTEM_ADMIN', updated_at = NOW()
WHERE id IN (
    SELECT DISTINCT ON (tenant_id) id
    FROM users
    WHERE role = 'HR_ADMIN'
    ORDER BY tenant_id, created_at ASC
);
