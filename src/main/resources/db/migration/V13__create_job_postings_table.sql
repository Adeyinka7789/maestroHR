CREATE TABLE IF NOT EXISTS job_postings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    department VARCHAR(100),
    location VARCHAR(200),
    employment_type VARCHAR(50) DEFAULT 'FULL_TIME',
    salary_range_min BIGINT,
    salary_range_max BIGINT,
    description TEXT NOT NULL,
    requirements TEXT,
    benefits TEXT,
    status VARCHAR(50) DEFAULT 'DRAFT',
    posted_date DATE,
    closing_date DATE,
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_job_postings_tenant ON job_postings(tenant_id);
CREATE INDEX idx_job_postings_status ON job_postings(status);
CREATE INDEX idx_job_postings_department ON job_postings(department);