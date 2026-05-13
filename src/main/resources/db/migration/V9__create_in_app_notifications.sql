CREATE TABLE in_app_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID,
    recipient_email VARCHAR(255) NOT NULL,
    type VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    link VARCHAR(255),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_in_app_notifications_recipient_email ON in_app_notifications(recipient_email, is_read, created_at DESC);
CREATE INDEX idx_in_app_notifications_tenant_id ON in_app_notifications(tenant_id);

CREATE TRIGGER in_app_notifications_updated_at
    BEFORE UPDATE ON in_app_notifications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
