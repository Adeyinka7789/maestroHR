CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_name VARCHAR(200) NOT NULL,
    rc_number VARCHAR(50) UNIQUE,
    industry VARCHAR(100) NOT NULL,
    company_size VARCHAR(50) NOT NULL,
    subscription_plan VARCHAR(50) NOT NULL DEFAULT 'FREE_TRIAL',
    subscription_expires_at TIMESTAMPTZ NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_is_active ON tenants(is_active);