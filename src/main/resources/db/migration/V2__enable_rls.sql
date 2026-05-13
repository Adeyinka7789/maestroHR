ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_select ON tenants
    FOR SELECT
    USING (id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation_insert ON tenants
    FOR INSERT
    WITH CHECK (id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation_update ON tenants
    FOR UPDATE
    USING (id = current_setting('app.current_tenant', true)::uuid);

CREATE POLICY tenant_isolation_delete ON tenants
    FOR DELETE
    USING (id = current_setting('app.current_tenant', true)::uuid);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();