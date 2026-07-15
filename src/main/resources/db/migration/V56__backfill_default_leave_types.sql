-- Backfill default leave types for existing tenants that have none.
--
-- Root cause: LeaveService.createDefaultLeaveTypes() was never wired into tenant registration, so
-- tenants provisioned before this fix have zero rows in leave_types — leaving the "Apply for Leave"
-- picker empty in production. New tenants are now seeded in TenantUserWrites.provisionTenantWithAdmin;
-- this one-off migration gives the same 7 defaults to every existing tenant that currently has NONE.
--
-- Only tenants with zero leave types are touched (WHERE NOT EXISTS), so any tenant that customized
-- or deleted its leave types is left exactly as-is. Soft-deleted tenants (V55) are skipped. Runs as
-- the Flyway/postgres role, so RLS does not filter the cross-tenant insert.
INSERT INTO leave_types
    (tenant_id, name, code, max_days_per_year, is_paid, requires_approval, carry_over_allowed, max_carry_over_days)
SELECT t.id, d.name, d.code, d.max_days, d.is_paid, d.requires_approval, d.carry_over, d.max_carry_over
FROM tenants t
CROSS JOIN (VALUES
    ('Annual Leave',    'ANNUAL',    20, TRUE,  TRUE, TRUE,   5),
    ('Sick Leave',      'SICK',      12, TRUE,  TRUE, FALSE,  0),
    ('Maternity Leave', 'MATERNITY', 60, TRUE,  TRUE, FALSE,  0),
    ('Paternity Leave', 'PATERNITY', 14, TRUE,  TRUE, FALSE,  0),
    ('Casual Leave',    'CASUAL',     5, TRUE,  TRUE, FALSE,  0),
    ('Unpaid Leave',    'UNPAID',    30, FALSE, TRUE, FALSE,  0),
    ('Study Leave',     'STUDY',    360, FALSE, TRUE, TRUE,  360)
) AS d(name, code, max_days, is_paid, requires_approval, carry_over, max_carry_over)
WHERE t.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM leave_types lt WHERE lt.tenant_id = t.id)
ON CONFLICT (tenant_id, code) DO NOTHING;
