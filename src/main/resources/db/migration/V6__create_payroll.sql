-- V6__create_payroll.sql
-- Creates payroll_runs and payroll_entries tables with RLS policies

-- =====================================================
-- Table: payroll_runs
-- =====================================================
CREATE TABLE payroll_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payroll_month INTEGER NOT NULL CHECK (payroll_month BETWEEN 1 AND 12),
    payroll_year INTEGER NOT NULL CHECK (payroll_year >= 2020),
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    total_gross BIGINT NOT NULL DEFAULT 0,
    total_net BIGINT NOT NULL DEFAULT 0,
    total_paye BIGINT NOT NULL DEFAULT 0,
    total_pension_employee BIGINT NOT NULL DEFAULT 0,
    total_pension_employer BIGINT NOT NULL DEFAULT 0,
    total_nhf BIGINT NOT NULL DEFAULT 0,
    initiated_by UUID NOT NULL REFERENCES users(id),
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_payroll_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'DISBURSING', 'COMPLETED', 'REJECTED'))
);

-- Indexes for payroll_runs
CREATE INDEX idx_payroll_runs_tenant_id ON payroll_runs(tenant_id);
CREATE INDEX idx_payroll_runs_month_year ON payroll_runs(payroll_month, payroll_year);
CREATE INDEX idx_payroll_runs_status ON payroll_runs(status);
CREATE INDEX idx_payroll_runs_initiated_by ON payroll_runs(initiated_by);

-- Enable RLS on payroll_runs
ALTER TABLE payroll_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_runs FORCE ROW LEVEL SECURITY;

-- RLS Policies for payroll_runs
CREATE POLICY payroll_runs_tenant_isolation_select ON payroll_runs
    FOR SELECT USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY payroll_runs_tenant_isolation_insert ON payroll_runs
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY payroll_runs_tenant_isolation_update ON payroll_runs
    FOR UPDATE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY payroll_runs_tenant_isolation_delete ON payroll_runs
    FOR DELETE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- =====================================================
-- Table: payroll_entries
-- =====================================================
CREATE TABLE payroll_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payroll_run_id UUID NOT NULL REFERENCES payroll_runs(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(id),
    basic_salary BIGINT NOT NULL,
    housing_allowance BIGINT NOT NULL DEFAULT 0,
    transport_allowance BIGINT NOT NULL DEFAULT 0,
    other_allowances BIGINT NOT NULL DEFAULT 0,
    gross_salary BIGINT NOT NULL,
    pension_employee BIGINT NOT NULL,
    pension_employer BIGINT NOT NULL,
    nhf_deduction BIGINT NOT NULL,
    paye_tax BIGINT NOT NULL,
    other_deductions BIGINT NOT NULL DEFAULT 0,
    net_salary BIGINT NOT NULL,
    days_worked INTEGER NOT NULL,
    working_days INTEGER NOT NULL,
    is_prorated BOOLEAN NOT NULL DEFAULT FALSE,
    transfer_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    transfer_reference VARCHAR(200),
    payslip_generated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_transfer_status CHECK (transfer_status IN ('PENDING', 'SUCCESS', 'FAILED'))
);

-- Indexes for payroll_entries
CREATE INDEX idx_payroll_entries_tenant_id ON payroll_entries(tenant_id);
CREATE INDEX idx_payroll_entries_payroll_run_id ON payroll_entries(payroll_run_id);
CREATE INDEX idx_payroll_entries_employee_id ON payroll_entries(employee_id);
CREATE INDEX idx_payroll_entries_transfer_status ON payroll_entries(transfer_status);

-- Enable RLS on payroll_entries
ALTER TABLE payroll_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_entries FORCE ROW LEVEL SECURITY;

-- RLS Policies for payroll_entries
CREATE POLICY payroll_entries_tenant_isolation_select ON payroll_entries
    FOR SELECT USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY payroll_entries_tenant_isolation_insert ON payroll_entries
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY payroll_entries_tenant_isolation_update ON payroll_entries
    FOR UPDATE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY payroll_entries_tenant_isolation_delete ON payroll_entries
    FOR DELETE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- =====================================================
-- Triggers for updated_at
-- =====================================================
CREATE TRIGGER payroll_runs_updated_at
    BEFORE UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Comments for documentation
COMMENT ON TABLE payroll_runs IS 'Main payroll run header record';
COMMENT ON TABLE payroll_entries IS 'Individual employee payroll calculation entries';
COMMENT ON COLUMN payroll_entries.pension_employee IS '8% of pensionable earnings (kobo)';
COMMENT ON COLUMN payroll_entries.pension_employer IS '10% of pensionable earnings (kobo)';
COMMENT ON COLUMN payroll_entries.nhf_deduction IS '2.5% of basic salary (kobo)';
COMMENT ON COLUMN payroll_entries.paye_tax IS 'Progressive tax computed monthly (kobo)';