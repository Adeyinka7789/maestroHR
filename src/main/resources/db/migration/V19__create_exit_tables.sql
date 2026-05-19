-- Exit Management Tables (idempotent)

CREATE TABLE IF NOT EXISTS exit_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    exit_type VARCHAR(50),
    last_working_day DATE,
    reason TEXT,
    status VARCHAR(50),
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS clearance_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    department VARCHAR(100),
    sort_order INTEGER,
    is_required BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS employee_clearance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    exit_request_id UUID NOT NULL,
    clearance_item_id UUID NOT NULL,
    status VARCHAR(50),
    cleared_by VARCHAR(255),
    cleared_at TIMESTAMP WITH TIME ZONE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS final_settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    exit_request_id UUID NOT NULL,
    unpaid_salary DECIMAL(15,2),
    accrued_leave DECIMAL(15,2),
    severance_pay DECIMAL(15,2),
    other_deductions DECIMAL(15,2),
    total_payable DECIMAL(15,2),
    payment_status VARCHAR(50),
    payment_date DATE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes safely
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_exit_requests_tenant') THEN
        CREATE INDEX idx_exit_requests_tenant ON exit_requests(tenant_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_exit_requests_employee') THEN
        CREATE INDEX idx_exit_requests_employee ON exit_requests(employee_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_employee_clearance_exit') THEN
        CREATE INDEX idx_employee_clearance_exit ON employee_clearance(exit_request_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_final_settlements_exit') THEN
        CREATE INDEX idx_final_settlements_exit ON final_settlements(exit_request_id);
    END IF;
END $$;

-- Insert default clearance items (only if the table is empty)
INSERT INTO clearance_items (id, tenant_id, name, department, sort_order, is_required, created_at, updated_at)
SELECT gen_random_uuid(), t.id, 'Return Laptop', 'IT', 1, true, NOW(), NOW()
FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM clearance_items WHERE name = 'Return Laptop' AND tenant_id = t.id);

INSERT INTO clearance_items (id, tenant_id, name, department, sort_order, is_required, created_at, updated_at)
SELECT gen_random_uuid(), t.id, 'Return Access Card', 'Security', 2, true, NOW(), NOW()
FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM clearance_items WHERE name = 'Return Access Card' AND tenant_id = t.id);

INSERT INTO clearance_items (id, tenant_id, name, department, sort_order, is_required, created_at, updated_at)
SELECT gen_random_uuid(), t.id, 'Settle Dues', 'Finance', 3, true, NOW(), NOW()
FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM clearance_items WHERE name = 'Settle Dues' AND tenant_id = t.id);

INSERT INTO clearance_items (id, tenant_id, name, department, sort_order, is_required, created_at, updated_at)
SELECT gen_random_uuid(), t.id, 'Exit Interview', 'HR', 4, false, NOW(), NOW()
FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM clearance_items WHERE name = 'Exit Interview' AND tenant_id = t.id);