-- V45__add_tenant_composite_indexes.sql
-- Composite indexes to support tenant-scoped repository queries (Step 10)

-- Leave requests: employee lookups scoped to tenant
CREATE INDEX IF NOT EXISTS idx_leave_requests_employee_tenant
ON leave_requests(employee_id, tenant_id);

-- Attendance records: employee+date lookups scoped to tenant
CREATE INDEX IF NOT EXISTS idx_attendance_employee_tenant_date
ON attendance_records(employee_id, tenant_id, attendance_date);

-- Employee loans: employee lookups scoped to tenant
CREATE INDEX IF NOT EXISTS idx_employee_loans_employee_tenant
ON employee_loans(employee_id, tenant_id);

-- Payroll entries: run lookups scoped to tenant
CREATE INDEX IF NOT EXISTS idx_payroll_entries_run_tenant
ON payroll_entries(payroll_run_id, tenant_id);

-- Payroll entries: employee lookups scoped to tenant
CREATE INDEX IF NOT EXISTS idx_payroll_entries_employee_tenant
ON payroll_entries(employee_id, tenant_id);

-- Loan repayments: run lookups scoped to tenant
CREATE INDEX IF NOT EXISTS idx_loan_repayments_run_tenant
ON loan_repayments(payroll_run_id, tenant_id);