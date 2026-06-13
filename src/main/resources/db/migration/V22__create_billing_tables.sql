-- V22__create_billing_tables.sql
-- Billing foundation: tenant_subscriptions (single source of truth for a tenant's
-- subscription state) and invoices (one row per payment attempt, idempotent on the
-- Paystack reference).
--
-- Backfill seeds tenant_subscriptions from the existing flat columns on `tenants`.
-- The `tenants.subscription_*` columns are intentionally LEFT IN PLACE for now so the
-- cutover is reversible; a later migration can drop them once all reads have moved off.
--
-- RLS is ENABLE-only (not FORCE), matching V20: Flyway runs as the table owner and is
-- bypassed by ENABLE-only RLS, so the backfill below is unaffected. NULLIF maps the
-- empty-string "no tenant" sentinel to NULL so comparisons return no rows safely.

-- =====================================================
-- Table: tenant_subscriptions
-- =====================================================
CREATE TABLE tenant_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan VARCHAR(50) NOT NULL,
    period VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    auto_renew BOOLEAN NOT NULL DEFAULT TRUE,
    paystack_subscription_code VARCHAR(255),
    paystack_customer_code VARCHAR(255),
    price_kobo BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_tenant_subscription_tenant UNIQUE(tenant_id)
);

CREATE TRIGGER tenant_subscriptions_updated_at
    BEFORE UPDATE ON tenant_subscriptions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- =====================================================
-- Table: invoices
-- =====================================================
CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    subscription_id UUID REFERENCES tenant_subscriptions(id) ON DELETE SET NULL,
    paystack_reference VARCHAR(255) NOT NULL,
    amount_kobo BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    plan VARCHAR(50) NOT NULL,
    period VARCHAR(20),
    paid_at TIMESTAMPTZ,
    failure_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_invoice_paystack_reference UNIQUE(paystack_reference)
);

CREATE INDEX idx_invoices_tenant_id ON invoices(tenant_id);

CREATE TRIGGER invoices_updated_at
    BEFORE UPDATE ON invoices
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- =====================================================
-- Backfill tenant_subscriptions from existing tenant state.
-- One row per tenant. price_kobo is resolved from pricing_config (seeded in V12)
-- for the tenant's (plan, period); falls back to 0 if no matching price row.
-- =====================================================
INSERT INTO tenant_subscriptions (
    id, tenant_id, plan, period, status,
    current_period_start, current_period_end, auto_renew,
    paystack_subscription_code, paystack_customer_code, price_kobo,
    created_at, updated_at
)
SELECT
    gen_random_uuid(),
    t.id,
    t.subscription_plan,
    COALESCE(t.payment_period, 'MONTHLY'),
    CASE
        WHEN t.subscription_plan = 'FREE_TRIAL'      THEN 'TRIALING'
        WHEN t.subscription_expires_at < NOW()       THEN 'EXPIRED'
        ELSE 'ACTIVE'
    END,
    t.created_at,
    t.subscription_expires_at,
    COALESCE(t.auto_renew, TRUE),
    t.paystack_subscription_code,
    t.paystack_customer_code,
    COALESCE(
        (SELECT pc.price_kobo
           FROM pricing_config pc
          WHERE pc.plan_name = t.subscription_plan
            AND pc.period    = COALESCE(t.payment_period, 'MONTHLY')),
        0
    ),
    t.created_at,
    NOW()
FROM tenants t;

-- =====================================================
-- Row Level Security
-- =====================================================
ALTER TABLE tenant_subscriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_subscriptions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoices
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
