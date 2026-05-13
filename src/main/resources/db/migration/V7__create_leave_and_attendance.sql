-- V8__create_leave_and_attendance.sql
-- Creates leave_types, leave_requests, and attendance_records tables with RLS

-- =====================================================
-- Table: leave_types (Configurable per tenant)
-- =====================================================
CREATE TABLE leave_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(20) NOT NULL,
    max_days_per_year INTEGER NOT NULL,
    is_paid BOOLEAN NOT NULL DEFAULT TRUE,
    requires_approval BOOLEAN NOT NULL DEFAULT TRUE,
    carry_over_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    max_carry_over_days INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_leave_type_code_per_tenant UNIQUE(tenant_id, code)
);

-- =====================================================
-- Table: leave_requests
-- =====================================================
CREATE TABLE leave_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    days_requested INTEGER NOT NULL,
    reason TEXT NOT NULL,
    cover_officer_id UUID REFERENCES employees(id),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    approved_by_id UUID REFERENCES users(id),
    approval_comment TEXT,
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_leave_dates CHECK (start_date <= end_date),
    CONSTRAINT chk_leave_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

-- =====================================================
-- Table: leave_balances (Track annual balances)
-- =====================================================
CREATE TABLE leave_balances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(id),
    leave_type_id UUID NOT NULL REFERENCES leave_types(id),
    year INTEGER NOT NULL,
    total_days_entitled INTEGER NOT NULL,
    days_taken INTEGER NOT NULL DEFAULT 0,
    days_carried_over INTEGER NOT NULL DEFAULT 0,
    days_remaining INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_leave_balance_per_employee_year UNIQUE(tenant_id, employee_id, leave_type_id, year)
);

-- =====================================================
-- Table: attendance_records
-- =====================================================
CREATE TABLE attendance_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(id),
    attendance_date DATE NOT NULL,
    clock_in_time TIME,
    clock_out_time TIME,
    hours_worked DECIMAL(5,2),
    status VARCHAR(50) NOT NULL DEFAULT 'PRESENT',
    check_in_method VARCHAR(20) DEFAULT 'MANUAL',
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_attendance_status CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'ON_LEAVE')),
    CONSTRAINT uk_daily_attendance UNIQUE(tenant_id, employee_id, attendance_date)
);

-- =====================================================
-- Indexes
-- =====================================================
CREATE INDEX idx_leave_requests_tenant_id ON leave_requests(tenant_id);
CREATE INDEX idx_leave_requests_employee_id ON leave_requests(employee_id);
CREATE INDEX idx_leave_requests_status ON leave_requests(status);
CREATE INDEX idx_leave_requests_dates ON leave_requests(start_date, end_date);

CREATE INDEX idx_leave_balances_employee_id ON leave_balances(employee_id);
CREATE INDEX idx_leave_balances_year ON leave_balances(year);

CREATE INDEX idx_attendance_records_tenant_id ON attendance_records(tenant_id);
CREATE INDEX idx_attendance_records_employee_id ON attendance_records(employee_id);
CREATE INDEX idx_attendance_records_date ON attendance_records(attendance_date);

-- =====================================================
-- Enable RLS on all tables
-- =====================================================
ALTER TABLE leave_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;

-- =====================================================
-- RLS Policies for leave_types
-- =====================================================
CREATE POLICY leave_types_tenant_isolation_select ON leave_types
    FOR SELECT USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_types_tenant_isolation_insert ON leave_types
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_types_tenant_isolation_update ON leave_types
    FOR UPDATE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_types_tenant_isolation_delete ON leave_types
    FOR DELETE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- =====================================================
-- RLS Policies for leave_requests
-- =====================================================
CREATE POLICY leave_requests_tenant_isolation_select ON leave_requests
    FOR SELECT USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_requests_tenant_isolation_insert ON leave_requests
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_requests_tenant_isolation_update ON leave_requests
    FOR UPDATE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_requests_tenant_isolation_delete ON leave_requests
    FOR DELETE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- =====================================================
-- RLS Policies for leave_balances
-- =====================================================
CREATE POLICY leave_balances_tenant_isolation_select ON leave_balances
    FOR SELECT USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_balances_tenant_isolation_insert ON leave_balances
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_balances_tenant_isolation_update ON leave_balances
    FOR UPDATE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY leave_balances_tenant_isolation_delete ON leave_balances
    FOR DELETE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- =====================================================
-- RLS Policies for attendance_records
-- =====================================================
CREATE POLICY attendance_records_tenant_isolation_select ON attendance_records
    FOR SELECT USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY attendance_records_tenant_isolation_insert ON attendance_records
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY attendance_records_tenant_isolation_update ON attendance_records
    FOR UPDATE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
CREATE POLICY attendance_records_tenant_isolation_delete ON attendance_records
    FOR DELETE USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- =====================================================
-- Triggers for updated_at
-- =====================================================
CREATE TRIGGER leave_types_updated_at
    BEFORE UPDATE ON leave_types FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER leave_requests_updated_at
    BEFORE UPDATE ON leave_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER leave_balances_updated_at
    BEFORE UPDATE ON leave_balances FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER attendance_records_updated_at
    BEFORE UPDATE ON attendance_records FOR EACH ROW EXECUTE FUNCTION set_updated_at();