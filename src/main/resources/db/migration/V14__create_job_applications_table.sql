CREATE TABLE IF NOT EXISTS job_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    job_posting_id UUID NOT NULL REFERENCES job_postings(id),
    applicant_name VARCHAR(200) NOT NULL,
    applicant_email VARCHAR(255) NOT NULL,
    applicant_phone VARCHAR(50),
    resume_url VARCHAR(500),
    cover_letter TEXT,
    status VARCHAR(50) DEFAULT 'NEW',
    source VARCHAR(50) DEFAULT 'WEBSITE',
    notes TEXT,
    interview_date TIMESTAMP,
    rating INTEGER,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    converted_to_employee_id UUID
);

CREATE INDEX idx_job_applications_tenant ON job_applications(tenant_id);
CREATE INDEX idx_job_applications_job ON job_applications(job_posting_id);
CREATE INDEX idx_job_applications_status ON job_applications(status);
CREATE INDEX idx_job_applications_email ON job_applications(applicant_email);