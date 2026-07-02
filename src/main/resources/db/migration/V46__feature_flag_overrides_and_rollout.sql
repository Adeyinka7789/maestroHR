-- V46__feature_flag_overrides_and_rollout.sql
-- Layered feature-flag resolution: per-tenant/per-plan overrides + rollout percentage
-- on top of the existing platform_flags global kill switch (V30).

CREATE TABLE feature_flag_overrides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_name VARCHAR(100) NOT NULL,
    target_type VARCHAR(20) NOT NULL, -- 'TENANT', 'PLAN'
    target_value VARCHAR(100) NOT NULL, -- tenant UUID string or plan name e.g. 'PROFESSIONAL'
    enabled BOOLEAN NOT NULL,
    reason VARCHAR(500),
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(flag_name, target_type, target_value)
);
-- No RLS - platform-level table, privileged access only (same as platform_flags, V30)

ALTER TABLE platform_flags ADD COLUMN IF NOT EXISTS rollout_percentage INTEGER NOT NULL DEFAULT 100;
-- 100 = everyone, 50 = half of tenants, 0 = nobody (but global enabled=true)
