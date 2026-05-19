-- Exit requests
CREATE TABLE IF NOT EXISTS exit_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    exit_type VARCHAR(50) NOT NULL,
    last_working_day DATE NOT NULL,
    reason TEXT,
    status VARCHAR(50) DEFAULT 'PENDING',
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Clearance checklist items
CREATE TABLE IF NOT EXISTS clearance_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    department VARCHAR(100),
    sort_order INTEGER DEFAULT 0,
    is_required BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Employee clearance tracking
CREATE TABLE IF NOT EXISTS employee_clearance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    exit_request_id UUID NOT NULL REFERENCES exit_requests(id),
    clearance_item_id UUID NOT NULL REFERENCES clearance_items(id),
    status VARCHAR(50) DEFAULT 'PENDING',
    cleared_by VARCHAR(255),
    cleared_at TIMESTAMP WITH TIME ZONE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Final settlement calculations
CREATE TABLE IF NOT EXISTS final_settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    exit_request_id UUID NOT NULL REFERENCES exit_requests(id),
    unpaid_salary DECIMAL(15,2),
    accrued_leave DECIMAL(15,2),
    severance_pay DECIMAL(15,2),
    other_deductions DECIMAL(15,2),
    total_payable DECIMAL(15,2),
    payment_status VARCHAR(50) DEFAULT 'PENDING',
    payment_date DATE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes
CREATE INDEX idx_exit_requests_employee ON exit_requests(employee_id);
CREATE INDEX idx_exit_requests_status ON exit_requests(status);
CREATE INDEX idx_employee_clearance_exit ON employee_clearance(exit_request_id);
CREATE INDEX idx_final_settlements_exit ON final_settlements(exit_request_id);