-- Add disbursement provider column to tenants
ALTER TABLE tenants
ADD COLUMN IF NOT EXISTS disbursement_provider VARCHAR(50) DEFAULT 'CSV';

-- Add index for faster lookups
CREATE INDEX IF NOT EXISTS idx_tenants_disbursement_provider ON tenants(disbursement_provider);