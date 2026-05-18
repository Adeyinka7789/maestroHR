-- Review templates
CREATE TABLE IF NOT EXISTS review_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    review_type VARCHAR(50) NOT NULL, -- ANNUAL, QUARTERLY, MONTHLY, PROBATION
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Review sections (e.g., Job Knowledge, Communication, Teamwork)
CREATE TABLE IF NOT EXISTS review_sections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES review_templates(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    weight INTEGER DEFAULT 0,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Review questions within sections
CREATE TABLE IF NOT EXISTS review_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id UUID NOT NULL REFERENCES review_sections(id) ON DELETE CASCADE,
    question_text TEXT NOT NULL,
    question_type VARCHAR(50) DEFAULT 'RATING', -- RATING, TEXT, YES_NO
    min_rating INTEGER DEFAULT 1,
    max_rating INTEGER DEFAULT 5,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Review cycles (actual reviews assigned to employees)
CREATE TABLE IF NOT EXISTS review_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    template_id UUID NOT NULL REFERENCES review_templates(id),
    employee_id UUID NOT NULL,
    reviewer_id UUID NOT NULL,
    review_period_start DATE NOT NULL,
    review_period_end DATE NOT NULL,
    due_date DATE,
    status VARCHAR(50) DEFAULT 'PENDING', -- PENDING, IN_PROGRESS, COMPLETED, OVERDUE
    self_review_status VARCHAR(50) DEFAULT 'NOT_STARTED',
    manager_review_status VARCHAR(50) DEFAULT 'NOT_STARTED',
    overall_rating DECIMAL(3,2),
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Review answers
CREATE TABLE IF NOT EXISTS review_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_cycle_id UUID NOT NULL REFERENCES review_cycles(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES review_questions(id),
    reviewer_type VARCHAR(50) NOT NULL, -- SELF, MANAGER, PEER
    rating INTEGER,
    answer_text TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Review goals (for future tracking)
CREATE TABLE IF NOT EXISTS review_goals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_cycle_id UUID NOT NULL REFERENCES review_cycles(id) ON DELETE CASCADE,
    goal_text TEXT NOT NULL,
    target_date DATE,
    status VARCHAR(50) DEFAULT 'NOT_STARTED', -- NOT_STARTED, IN_PROGRESS, COMPLETED, CANCELLED
    completion_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes
CREATE INDEX idx_review_templates_tenant ON review_templates(tenant_id);
CREATE INDEX idx_review_cycles_tenant ON review_cycles(tenant_id);
CREATE INDEX idx_review_cycles_employee ON review_cycles(employee_id);
CREATE INDEX idx_review_cycles_reviewer ON review_cycles(reviewer_id);
CREATE INDEX idx_review_cycles_status ON review_cycles(status);
CREATE INDEX idx_review_answers_cycle ON review_answers(review_cycle_id);
CREATE INDEX idx_review_goals_cycle ON review_goals(review_cycle_id);