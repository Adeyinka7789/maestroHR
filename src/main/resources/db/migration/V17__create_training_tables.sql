-- Training programs/courses
CREATE TABLE IF NOT EXISTS training_programs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    category VARCHAR(100),
    duration_hours INTEGER,
    trainer_name VARCHAR(200),
    trainer_email VARCHAR(255),
    max_participants INTEGER,
    cost DECIMAL(15,2),
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Employee training enrollments
CREATE TABLE IF NOT EXISTS employee_trainings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    training_id UUID NOT NULL REFERENCES training_programs(id),
    enrollment_date DATE NOT NULL,
    completion_date DATE,
    status VARCHAR(50) DEFAULT 'ENROLLED',
    score INTEGER,
    certificate_url VARCHAR(500),
    notes TEXT,
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Certifications
CREATE TABLE IF NOT EXISTS certifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    issuing_body VARCHAR(200),
    issue_date DATE NOT NULL,
    expiry_date DATE,
    certificate_url VARCHAR(500),
    reminder_sent BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes
CREATE INDEX idx_training_programs_tenant ON training_programs(tenant_id);
CREATE INDEX idx_employee_trainings_employee ON employee_trainings(employee_id);
CREATE INDEX idx_employee_trainings_training ON employee_trainings(training_id);
CREATE INDEX idx_employee_trainings_status ON employee_trainings(status);
CREATE INDEX idx_certifications_employee ON certifications(employee_id);
CREATE INDEX idx_certifications_expiry ON certifications(expiry_date);