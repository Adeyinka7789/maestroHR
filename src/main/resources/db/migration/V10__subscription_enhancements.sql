-- Add subscription columns to tenants table
ALTER TABLE tenants
ADD COLUMN IF NOT EXISTS paystack_subscription_code VARCHAR(255),
ADD COLUMN IF NOT EXISTS paystack_customer_code VARCHAR(255),
ADD COLUMN IF NOT EXISTS payment_period VARCHAR(20) DEFAULT 'MONTHLY',
ADD COLUMN IF NOT EXISTS auto_renew BOOLEAN DEFAULT TRUE;

-- Create subscription_plans table for audit trail
CREATE TABLE IF NOT EXISTS subscription_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    old_plan VARCHAR(50),
    new_plan VARCHAR(50) NOT NULL,
    payment_period VARCHAR(20),
    amount_paid_kobo BIGINT,
    transaction_reference VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Enable RLS on subscription_audit
ALTER TABLE subscription_audit ENABLE ROW LEVEL SECURITY;

CREATE POLICY subscription_audit_tenant_isolation ON subscription_audit
    FOR ALL USING (tenant_id = current_setting('app.current_tenant', true)::uuid);