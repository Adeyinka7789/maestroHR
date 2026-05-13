CREATE TABLE audit_trail (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID,
    actor_email VARCHAR(255),
    action VARCHAR(120) NOT NULL,
    entity_type VARCHAR(120),
    entity_id VARCHAR(120),
    request_path VARCHAR(255) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    ip_address VARCHAR(64),
    status_code INTEGER NOT NULL,
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_trail_created_at ON audit_trail(created_at DESC);
CREATE INDEX idx_audit_trail_tenant_id ON audit_trail(tenant_id);
CREATE INDEX idx_audit_trail_actor_email ON audit_trail(actor_email);
CREATE INDEX idx_audit_trail_action ON audit_trail(action);

CREATE TRIGGER audit_trail_updated_at
    BEFORE UPDATE ON audit_trail
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
