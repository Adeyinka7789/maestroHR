-- Enable PostgreSQL Row Level Security on all 22 tenant-scoped tables.
--
-- FORCE RLS is intentionally omitted: Flyway runs as the table owner, and
-- FORCE would block the owner's own queries during future migrations.
--
-- The app.current_tenant session variable is set by DataSourceConfig on every
-- connection checkout. NULLIF converts the empty string (written when no tenant
-- context is active) to NULL so that the comparison safely returns no rows
-- instead of throwing a UUID cast error.

-- ── Employees ────────────────────────────────────────────────────────────────

ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employees
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON departments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE pay_grades ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pay_grades
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Payroll ───────────────────────────────────────────────────────────────────

ALTER TABLE payroll_runs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payroll_runs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE payroll_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payroll_entries
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Leave ─────────────────────────────────────────────────────────────────────

ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE leave_balances ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_balances
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE leave_types ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_types
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Attendance ────────────────────────────────────────────────────────────────

ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attendance_records
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Exit Management ───────────────────────────────────────────────────────────

ALTER TABLE exit_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exit_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE employee_clearance ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_clearance
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE clearance_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON clearance_items
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE final_settlements ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON final_settlements
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Recruitment ───────────────────────────────────────────────────────────────

ALTER TABLE job_postings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_postings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE job_applications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_applications
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Training ──────────────────────────────────────────────────────────────────

ALTER TABLE training_programs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_programs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE employee_trainings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_trainings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE certifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON certifications
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Performance ───────────────────────────────────────────────────────────────

ALTER TABLE review_cycles ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON review_cycles
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE review_templates ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON review_templates
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Audit & Notifications ─────────────────────────────────────────────────────

ALTER TABLE audit_trail ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_trail
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE in_app_notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON in_app_notifications
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
