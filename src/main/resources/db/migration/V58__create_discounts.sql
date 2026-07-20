-- V58__create_discounts.sql
--
-- Admin-managed subscription discounts. Like pricing_config (V12), this is GLOBAL
-- platform configuration, not tenant-owned data: it is deliberately NOT given a
-- tenant_isolation RLS policy (V20 only enabled RLS on the 22 tenant-scoped tables).
-- The tenant_id column here is an optional *targeting filter* ("this discount is only
-- for customer X"), NOT an RLS scoping column. maestro_app receives DML on this table
-- automatically via the ALTER DEFAULT PRIVILEGES grant in V24.
--
-- Resolution at checkout (DiscountService): among all active, in-window discounts whose
-- tenant_id/plan_name/period either match the purchase or are NULL (= "applies to all"),
-- pick the one that yields the lowest net price. Discounts do not stack.

CREATE TABLE IF NOT EXISTS discounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label         VARCHAR(120) NOT NULL,

    -- PERCENTAGE uses percent_bps (basis points: 2000 = 20%); FIXED uses amount_kobo.
    discount_type VARCHAR(20)  NOT NULL,
    percent_bps   INTEGER,
    amount_kobo   BIGINT,

    -- Optional targeting. NULL means "applies to all" on that dimension.
    tenant_id     UUID,
    plan_name     VARCHAR(50),
    period        VARCHAR(20),

    -- Optional validity window. NULL = unbounded on that side.
    starts_at     TIMESTAMPTZ,
    ends_at       TIMESTAMPTZ,

    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT discount_type_chk CHECK (discount_type IN ('PERCENTAGE', 'FIXED')),
    CONSTRAINT discount_value_chk CHECK (
        (discount_type = 'PERCENTAGE' AND percent_bps IS NOT NULL AND percent_bps > 0 AND percent_bps <= 10000)
        OR
        (discount_type = 'FIXED' AND amount_kobo IS NOT NULL AND amount_kobo > 0)
    ),
    CONSTRAINT discount_window_chk CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at >= starts_at),

    -- Per-customer discounts are removed if the customer is deleted (mirrors the
    -- ON DELETE CASCADE convention adopted for tenant FKs in V55).
    CONSTRAINT fk_discounts_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE
);

-- Backs the active-discount lookup at checkout.
CREATE INDEX IF NOT EXISTS idx_discounts_lookup
    ON discounts (is_active, tenant_id, plan_name, period);

CREATE TRIGGER discounts_updated_at
    BEFORE UPDATE ON discounts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- Record what discount (if any) was applied to each invoice, so the settings-page
-- receipt can show Subtotal / Discount / Total. amount_kobo remains the NET amount
-- actually charged; discount_kobo is the amount taken off (0 when none).
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS discount_kobo  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discount_label VARCHAR(160);
