CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(100) NOT NULL,
    head_employee_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_departments_tenant_id ON departments(tenant_id);

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;

CREATE POLICY departments_tenant_isolation_select ON departments
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY departments_tenant_isolation_insert ON departments
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY departments_tenant_isolation_update ON departments
    FOR UPDATE
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY departments_tenant_isolation_delete ON departments
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE TRIGGER departments_updated_at
    BEFORE UPDATE ON departments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE pay_grades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(100) NOT NULL,
    basic_salary BIGINT NOT NULL,
    housing_allowance BIGINT NOT NULL DEFAULT 0,
    transport_allowance BIGINT NOT NULL DEFAULT 0,
    other_allowances BIGINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE INDEX idx_pay_grades_tenant_id ON pay_grades(tenant_id);

ALTER TABLE pay_grades ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_grades FORCE ROW LEVEL SECURITY;

CREATE POLICY pay_grades_tenant_isolation_select ON pay_grades
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY pay_grades_tenant_isolation_insert ON pay_grades
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY pay_grades_tenant_isolation_update ON pay_grades
    FOR UPDATE
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY pay_grades_tenant_isolation_delete ON pay_grades
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);

CREATE TRIGGER pay_grades_updated_at
    BEFORE UPDATE ON pay_grades
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();