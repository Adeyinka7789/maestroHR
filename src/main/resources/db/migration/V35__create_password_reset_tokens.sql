-- V35__create_password_reset_tokens.sql
--
-- Forgot-password flow. A user requests a reset by email; we mint a single-use,
-- time-boxed token (1 hour) and email them a link. Resetting consumes the token.
--
-- Cross-tenant / pre-tenant by nature: like login, both the request and the reset
-- run with NO tenant session bound (the user is, by definition, locked out). The
-- table therefore carries NO tenant_id and NO RLS policy — it is an auth-bootstrap
-- table, accessed exclusively through the privileged (postgres) datasource via
-- PasswordResetTokenStore, exactly as users/tenants lookups are during login.
--
-- maestro_app still receives DML on it through V24's ALTER DEFAULT PRIVILEGES (the
-- app never touches it on the primary pool, but the grant keeps the role consistent).

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email  VARCHAR(255) NOT NULL,
    token       UUID NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The reset path looks rows up by token.
CREATE INDEX idx_password_reset_tokens_token ON password_reset_tokens (token);
