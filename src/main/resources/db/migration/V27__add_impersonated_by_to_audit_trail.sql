-- Superadmin Feature 4 (Impersonation): tag audit rows written during an impersonation
-- session with the super-admin who initiated it. Nullable — only impersonation-token
-- requests populate it; all normal requests leave it NULL.
ALTER TABLE audit_trail ADD COLUMN impersonated_by VARCHAR(255);

CREATE INDEX idx_audit_trail_impersonated_by ON audit_trail(impersonated_by);
