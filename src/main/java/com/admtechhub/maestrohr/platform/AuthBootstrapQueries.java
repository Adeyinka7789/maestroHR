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
 * Phase E4b — privileged reads for the authentication / registration bootstrap path.
 *
 * <p>These run <em>before</em> a tenant session exists (login, cross-tenant email/RC
 * uniqueness, the tenant lookup that populates a login response). On the primary,
 * RLS-bound datasource they would return nothing once the runtime role is
 * {@code maestro_app}: {@code users} and {@code tenants} carry NULLIF tenant-isolation
 * policies (V25) and neither entity has an {@code @SQLRestriction} — they work today only
 * because the app still connects as postgres. Routed through the privileged datasource they
 * resolve regardless of {@code TenantContext} / {@code app.current_tenant}.
 *
 * <p><b>Reads only.</b> The matching writes (registration inserts, login failed-attempt
 * write-backs) are deferred to E4c. This class is not yet wired into {@code AuthService} /
 * {@code TenantService}.
 */
@Repository
public class AuthBootstrapQueries {

    private final JdbcTemplate jdbc;

    public AuthBootstrapQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Authentication fields for a user, resolved by email across all tenants (op 1). */
    public Optional<UserAuthRow> findUserByEmail(String email) {
        List<UserAuthRow> rows = jdbc.query(
                "SELECT id, tenant_id, email, password_hash, role, is_active, "
                        + "failed_login_attempts, locked_until "
                        + "FROM users WHERE email = ?",
                USER_AUTH_ROW, email);
        return rows.stream().findFirst();
    }

    /** Cross-tenant email-uniqueness check used by registration / admin user creation (op 2). */
    public boolean existsUserByEmail(String email) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE email = ?)", Boolean.class, email);
        return Boolean.TRUE.equals(exists);
    }

    /** Cross-tenant RC-number-uniqueness check used by tenant registration (op 3, read side). */
    public boolean existsTenantByRcNumber(String rcNumber) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tenants WHERE rc_number = ?)", Boolean.class, rcNumber);
        return Boolean.TRUE.equals(exists);
    }

    /** Tenant company name for a login response, resolved with no tenant session (op 1b). */
    public Optional<TenantNameRow> findTenantById(UUID tenantId) {
        List<TenantNameRow> rows = jdbc.query(
                "SELECT id, company_name FROM tenants WHERE id = ?",
                (rs, n) -> new TenantNameRow(
                        rs.getObject("id", UUID.class), rs.getString("company_name")),
                tenantId);
        return rows.stream().findFirst();
    }

    private static final RowMapper<UserAuthRow> USER_AUTH_ROW = (rs, n) -> new UserAuthRow(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getBoolean("is_active"),
            rs.getInt("failed_login_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class));

    /** The authentication-relevant projection of a {@code users} row. */
    public record UserAuthRow(
            UUID id,
            UUID tenantId,
            String email,
            String passwordHash,
            String role,
            boolean active,
            int failedLoginAttempts,
            OffsetDateTime lockedUntil) {
    }

    /** Minimal {@code tenants} projection for a login response. */
    public record TenantNameRow(UUID id, String companyName) {
    }
}
