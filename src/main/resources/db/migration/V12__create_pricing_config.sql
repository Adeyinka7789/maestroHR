-- Create pricing_config table for dynamic pricing
CREATE TABLE IF NOT EXISTS pricing_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_name VARCHAR(50) NOT NULL,
    period VARCHAR(20) NOT NULL,
    price_kobo BIGINT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_plan_period UNIQUE(plan_name, period)
);

-- Create trigger for updated_at
CREATE TRIGGER pricing_config_updated_at
    BEFORE UPDATE ON pricing_config
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- Insert default pricing
INSERT INTO pricing_config (plan_name, period, price_kobo) VALUES
    ('FREE_TRIAL', 'MONTHLY', 0),
    ('FREE_TRIAL', 'QUARTERLY', 0),
    ('FREE_TRIAL', 'ANNUALLY', 0),
    ('BASIC', 'MONTHLY', 25000),
    ('BASIC', 'QUARTERLY', 71250),
    ('BASIC', 'ANNUALLY', 270000),
    ('PROFESSIONAL', 'MONTHLY', 75000),
    ('PROFESSIONAL', 'QUARTERLY', 213750),
    ('PROFESSIONAL', 'ANNUALLY', 810000),
    ('ENTERPRISE', 'MONTHLY', 200000),
    ('ENTERPRISE', 'QUARTERLY', 570000),
    ('ENTERPRISE', 'ANNUALLY', 2160000);