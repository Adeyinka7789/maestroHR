package com.admtechhub.maestrohr.platform;

import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Phase E4b — privileged cross-tenant aggregates for the SUPER_ADMIN console (op 7).
 *
 * <p>These count and list across <em>all</em> tenants. Today they work because postgres
 * bypasses RLS and neither {@code users} nor {@code tenants} has an {@code @SQLRestriction};
 * under {@code maestro_app} the V25 tenant-isolation policies would collapse them to the
 * current tenant (or nothing). Routed through the privileged datasource they stay global.
 *
 * <p>Reads only — the admin write CRUD (create/update tenant & user) is deferred to E4c.
 * Not yet wired into {@code AdminApiController} / {@code AdminManagementController}.
 */
@Repository
public class AdminStatsQueries {

    private final JdbcTemplate jdbc;

    public AdminStatsQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countTenants() {
        return count("SELECT count(*) FROM tenants");
    }

    public long countActiveTenants() {
        return count("SELECT count(*) FROM tenants WHERE is_active = true");
    }

    public long countUsers() {
        return count("SELECT count(*) FROM users");
    }

    /** Mirrors {@code UserRepository.countLockedUsers()} — users with a lock still in effect. */
    public long countLockedUsers() {
        return count("SELECT count(*) FROM users WHERE locked_until > now()");
    }

    /** Mirrors {@code TenantRepository.findAllWithUserCount()}, across all tenants. */
    public List<TenantWithUserCountDTO> findAllTenantsWithUserCount() {
        return jdbc.query(
                "SELECT t.id, t.company_name, t.rc_number, t.industry, t.company_size, "
                        + "t.subscription_plan, t.subscription_expires_at, t.is_active, "
                        + "COUNT(u.id) AS user_count "
                        + "FROM tenants t LEFT JOIN users u ON u.tenant_id = t.id "
                        + "GROUP BY t.id, t.company_name, t.rc_number, t.industry, t.company_size, "
                        + "t.subscription_plan, t.subscription_expires_at, t.is_active "
                        + "ORDER BY t.created_at DESC",
                (rs, n) -> new TenantWithUserCountDTO(
                        rs.getObject("id", UUID.class),
                        rs.getString("company_name"),
                        rs.getString("rc_number"),
                        rs.getString("industry"),
                        rs.getString("company_size"),
                        SubscriptionPlan.valueOf(rs.getString("subscription_plan")),
                        rs.getObject("subscription_expires_at", OffsetDateTime.class),
                        rs.getBoolean("is_active"),
                        rs.getLong("user_count")));
    }

    /** All users across every tenant, for the admin user list. */
    public List<AdminUserRow> findAllUsers() {
        return jdbc.query(
                "SELECT id, tenant_id, email, role, is_active, locked_until, created_at "
                        + "FROM users ORDER BY created_at DESC",
                ADMIN_USER_ROW);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value != null ? value : 0L;
    }

    private static final RowMapper<AdminUserRow> ADMIN_USER_ROW = (rs, n) -> {
        OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
        boolean locked = lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
        return new AdminUserRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("email"),
                rs.getString("role"),
                rs.getBoolean("is_active"),
                locked,
                lockedUntil,
                rs.getObject("created_at", OffsetDateTime.class));
    };

    /**
     * Admin-list projection of a {@code users} row. {@code locked} is the derived
     * {@code lockedUntil > now} flag (mirrors {@code User.isLocked()}), kept on the
     * projection so the admin console's user list keeps the same JSON contract it had when
     * this endpoint returned the {@code User} entity — and without leaking the password hash.
     */
    public record AdminUserRow(
            UUID id,
            UUID tenantId,
            String email,
            String role,
            boolean active,
            boolean locked,
            OffsetDateTime lockedUntil,
            OffsetDateTime createdAt) {
    }
}
