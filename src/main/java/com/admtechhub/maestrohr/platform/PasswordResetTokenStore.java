package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Privileged store for the forgot-password tokens (V35).
 *
 * <p>The forgot-password request and the reset both run with no tenant session bound — the
 * user is locked out, so {@code TenantContext} is empty. The {@code password_reset_tokens}
 * table is therefore an auth-bootstrap table with no RLS, accessed only through the privileged
 * (postgres) datasource, exactly like the {@link AuthBootstrapQueries} login lookups.
 */
@Repository
public class PasswordResetTokenStore {

    private final JdbcTemplate jdbc;

    public PasswordResetTokenStore(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Mint a new reset token for the given email. */
    public void insert(String userEmail, UUID token, OffsetDateTime expiresAt) {
        jdbc.update(
                "INSERT INTO password_reset_tokens (id, user_email, token, expires_at, used) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, false)",
                userEmail, token, expiresAt);
    }

    /** Look a token up by its value (used by the reset path). */
    public Optional<TokenRow> findByToken(UUID token) {
        List<TokenRow> rows = jdbc.query(
                "SELECT id, user_email, token, expires_at, used "
                        + "FROM password_reset_tokens WHERE token = ?",
                TOKEN_ROW, token);
        return rows.stream().findFirst();
    }

    /** Consume a token so it cannot be reused. */
    public void markUsed(UUID id) {
        jdbc.update("UPDATE password_reset_tokens SET used = true WHERE id = ?", id);
    }

    private static final RowMapper<TokenRow> TOKEN_ROW = (rs, n) -> new TokenRow(
            rs.getObject("id", UUID.class),
            rs.getString("user_email"),
            rs.getObject("token", UUID.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getBoolean("used"));

    /** A {@code password_reset_tokens} row. */
    public record TokenRow(UUID id, String userEmail, UUID token, OffsetDateTime expiresAt, boolean used) {

        public boolean isExpired() {
            return expiresAt == null || expiresAt.isBefore(OffsetDateTime.now());
        }
    }
}
