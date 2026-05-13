-- V5__create_employees.sql
-- Creates employees table with RLS policies for multi-tenant isolation

CREATE TABLE employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    employee_number VARCHAR(50) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    date_of_birth DATE NOT NULL,
    gender VARCHAR(20) NOT NULL,
    marital_status VARCHAR(20) NOT NULL,
    address TEXT NOT NULL,
    nin_encrypted VARCHAR(500),
    bvn_encrypted VARCHAR(500),
    department_id UUID NOT NULL REFERENCES departments(id),
    pay_grade_id UUID NOT NULL REFERENCES pay_grades(id),
    job_title VARCHAR(150) NOT NULL,
    employment_type VARCHAR(50) NOT NULL,
    employment_start_date DATE NOT NULL,
    probation_end_date DATE,
    bank_name VARCHAR(100) NOT NULL,
    bank_account_number VARCHAR(20) NOT NULL,
    bank_account_name VARCHAR(200) NOT NULL,
    paystack_recipient_code VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    termination_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT uk_employee_number_per_tenant UNIQUE(tenant_id, employee_number),
    CONSTRAINT uk_employee_email_per_tenant UNIQUE(tenant_id, email),
    CONSTRAINT chk_gender CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT chk_marital_status CHECK (marital_status IN ('SINGLE', 'MARRIED', 'DIVORCED', 'WIDOWED')),
    CONSTRAINT chk_employment_type CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT')),
    CONSTRAINT chk_employee_status CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED'))
);

-- Indexes for performance
CREATE INDEX idx_employees_tenant_id ON employees(tenant_id);
CREATE INDEX idx_employees_department_id ON employees(department_id);
CREATE INDEX idx_employees_pay_grade_id ON employees(pay_grade_id);
CREATE INDEX idx_employees_status ON employees(status);
CREATE INDEX idx_employees_email ON employees(email);
CREATE INDEX idx_employees_employee_number ON employees(employee_number);

-- Enable Row Level Security
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;

-- RLS Policies
CREATE POLICY employees_tenant_isolation_select ON employees
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY employees_tenant_isolation_insert ON employees
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY employees_tenant_isolation_update ON employees
    FOR UPDATE
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY employees_tenant_isolation_delete ON employees
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Auto-update updated_at trigger
CREATE TRIGGER employees_updated_at
    BEFORE UPDATE ON employees
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();