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

    /**
     * Most-recently-created tenants across every tenant, for the super-admin dashboard's
     * "Recent Tenants" table. Includes {@code created_at} (which {@link TenantWithUserCountDTO}
     * omits) so the table can show a "Date Joined" column, and applies {@code LIMIT} so only the
     * handful the card renders are fetched.
     */
    public List<RecentTenantRow> findRecentTenants(int limit) {
        return jdbc.query(
                "SELECT t.company_name, t.rc_number, t.subscription_plan, t.is_active, t.created_at, "
                        + "COUNT(u.id) AS user_count "
                        + "FROM tenants t LEFT JOIN users u ON u.tenant_id = t.id "
                        + "GROUP BY t.id, t.company_name, t.rc_number, t.subscription_plan, t.is_active, t.created_at "
                        + "ORDER BY t.created_at DESC LIMIT ?",
                (rs, n) -> new RecentTenantRow(
                        rs.getString("company_name"),
                        rs.getString("rc_number"),
                        rs.getString("subscription_plan"),
                        rs.getBoolean("is_active"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getLong("user_count")),
                limit);
    }

    /**
     * Most-recent audit-trail entries across every tenant, for the dashboard's system-logs feed.
     * The {@code AuditTrail} entity carries an {@code @SQLRestriction} that scopes JPA reads to the
     * current tenant; this privileged native read deliberately spans all tenants instead.
     */
    public List<RecentLogRow> findRecentAuditLogs(int limit) {
        return jdbc.query(
                "SELECT action, actor_email, request_path, http_method, status_code, created_at "
                        + "FROM audit_trail ORDER BY created_at DESC LIMIT ?",
                (rs, n) -> new RecentLogRow(
                        rs.getString("action"),
                        rs.getString("actor_email"),
                        rs.getString("request_path"),
                        rs.getString("http_method"),
                        rs.getInt("status_code"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                limit);
    }

    /**
     * Full tenant detail for the super-admin detail view. Returns null if the tenant id is unknown.
     * Five separate privileged queries assembled into a single DTO — all bypass RLS so they span
     * the target tenant regardless of the admin's own session.
     */
    public TenantDetailDTO findTenantDetail(UUID id) {
        List<TenantInfoRow> tenants = jdbc.query(
                "SELECT id, company_name, rc_number, industry, company_size, is_active, created_at "
                        + "FROM tenants WHERE id = ?",
                (rs, n) -> new TenantInfoRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("company_name"),
                        rs.getString("rc_number"),
                        rs.getString("industry"),
                        rs.getString("company_size"),
                        rs.getBoolean("is_active"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                id);
        if (tenants.isEmpty()) return null;

        List<SubscriptionRow> subs = jdbc.query(
                "SELECT plan, status, current_period_start, current_period_end, price_kobo "
                        + "FROM tenant_subscriptions WHERE tenant_id = ?",
                (rs, n) -> new SubscriptionRow(
                        rs.getString("plan"),
                        rs.getString("status"),
                        rs.getObject("current_period_start", OffsetDateTime.class),
                        rs.getObject("current_period_end", OffsetDateTime.class),
                        rs.getLong("price_kobo")),
                id);

        List<InvoiceRow> invoices = jdbc.query(
                "SELECT id, amount_kobo, status, plan, period, paid_at, created_at "
                        + "FROM invoices WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 5",
                (rs, n) -> new InvoiceRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("amount_kobo"),
                        rs.getString("status"),
                        rs.getString("plan"),
                        rs.getString("period"),
                        rs.getObject("paid_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)),
                id);

        List<TenantUserRow> users = jdbc.query(
                "SELECT id, email, role, is_active, created_at, last_login_at "
                        + "FROM users WHERE tenant_id = ? ORDER BY created_at DESC",
                (rs, n) -> new TenantUserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getBoolean("is_active"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_login_at", OffsetDateTime.class)),
                id);

        List<RecentLogRow> activity = jdbc.query(
                "SELECT action, actor_email, request_path, http_method, status_code, created_at "
                        + "FROM audit_trail WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 5",
                (rs, n) -> new RecentLogRow(
                        rs.getString("action"),
                        rs.getString("actor_email"),
                        rs.getString("request_path"),
                        rs.getString("http_method"),
                        rs.getInt("status_code"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                id);

        return new TenantDetailDTO(tenants.get(0), subs.isEmpty() ? null : subs.get(0),
                invoices, users, activity);
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

    /** A row of the super-admin dashboard's "Recent Tenants" table. */
    public record RecentTenantRow(
            String companyName,
            String rcNumber,
            String subscriptionPlan,
            boolean active,
            OffsetDateTime createdAt,
            long userCount) {
    }

    /** A row of the super-admin dashboard's system-logs feed. */
    public record RecentLogRow(
            String action,
            String actorEmail,
            String requestPath,
            String httpMethod,
            int statusCode,
            OffsetDateTime createdAt) {
    }

    /** Base tenant info for the admin detail view header. */
    public record TenantInfoRow(
            UUID id,
            String companyName,
            String rcNumber,
            String industry,
            String companySize,
            boolean active,
            OffsetDateTime createdAt) {
    }

    /** Subscription state for the admin detail view plan card. */
    public record SubscriptionRow(
            String plan,
            String status,
            OffsetDateTime currentPeriodStart,
            OffsetDateTime currentPeriodEnd,
            long priceKobo) {
    }

    /** One invoice row on the admin detail view. */
    public record InvoiceRow(
            UUID id,
            long amountKobo,
            String status,
            String plan,
            String period,
            OffsetDateTime paidAt,
            OffsetDateTime createdAt) {
    }

    /** One user row on the admin detail view (no password hash). */
    public record TenantUserRow(
            UUID id,
            String email,
            String role,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime lastLoginAt) {
    }

    /** Full tenant detail DTO returned by {@code GET /api/admin/tenants/{id}/detail}. */
    public record TenantDetailDTO(
            TenantInfoRow tenant,
            SubscriptionRow subscription,
            List<InvoiceRow> recentInvoices,
            List<TenantUserRow> users,
            List<RecentLogRow> recentActivity) {
    }
}
