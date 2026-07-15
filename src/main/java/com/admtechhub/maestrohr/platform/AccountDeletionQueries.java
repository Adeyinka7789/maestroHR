package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Privileged backing for self-service account/company management (V55). Every operation here runs
 * with no reliance on the caller's tenant session: listing the companies a person belongs to is
 * inherently cross-tenant (one email, many companies), and the soft-delete flips both the tenant
 * row and its users' rows — so all of it goes through the privileged ({@code postgres}) datasource,
 * the same reasoning as {@link AuthBootstrapQueries} / {@link TenantUserWrites}.
 *
 * <p>Authorization (does the caller actually belong to / own the company?) is enforced one layer up
 * in {@code AccountService}; this class is the raw data access. Restore and the eventual hard purge
 * of a soft-deleted company live in {@link DeletedRecordsQueries} / {@link SoftDeleteCleanupQueries}
 * with the rest of the trash machinery.
 */
@Repository
public class AccountDeletionQueries {

    private final JdbcTemplate jdbc;

    public AccountDeletionQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One company the caller belongs to: its tenant id, name, the caller's role there, and the total member count. */
    public record OwnedCompany(UUID tenantId, String companyName, String role, long memberCount) {}

    /**
     * Every live (non-trashed) company the email belongs to, name-sorted, each with the caller's
     * role and the company's member count (used to block "leave" when they are the only member).
     */
    public List<OwnedCompany> findCompaniesForEmail(String email) {
        return jdbc.query(
                "SELECT u.tenant_id, t.company_name, u.role, "
                        + "  (SELECT COUNT(*) FROM users u2 WHERE u2.tenant_id = u.tenant_id) AS member_count "
                        + "FROM users u JOIN tenants t ON t.id = u.tenant_id "
                        + "WHERE u.email = ? AND t.deleted_at IS NULL "
                        + "ORDER BY t.company_name",
                (rs, n) -> new OwnedCompany(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("company_name"),
                        rs.getString("role"),
                        rs.getLong("member_count")),
                email);
    }

    /** Remove the caller's membership row for a company ("leave company"). @return rows deleted (0 if none). */
    public int leaveCompany(String email, UUID tenantId) {
        return jdbc.update("DELETE FROM users WHERE email = ? AND tenant_id = ?", email, tenantId);
    }

    /**
     * Soft-delete a company: stamp {@code deleted_at}, deactivate the tenant and all its users, all
     * in one transaction so a partial state can't leave a "deleted" tenant whose users are still
     * active. @return true if the tenant was live and is now trashed, false if it was already
     * trashed / unknown.
     */
    public boolean softDeleteCompany(UUID tenantId) {
        Boolean done = jdbc.execute((Connection con) -> inTransaction(con, () -> {
            int updated = updateReturningCount(con,
                    "UPDATE tenants SET deleted_at = now(), is_active = false "
                            + "WHERE id = ? AND deleted_at IS NULL", tenantId);
            if (updated == 0) {
                return false;
            }
            updateReturningCount(con, "UPDATE users SET is_active = false WHERE tenant_id = ?", tenantId);
            return true;
        }));
        return Boolean.TRUE.equals(done);
    }

    // ── small JDBC helpers (mirror DeletedRecordsQueries) ────────────────────────────

    private int updateReturningCount(Connection con, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate();
        }
    }

    private Boolean inTransaction(Connection con, TxWork work) throws SQLException {
        boolean prev = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            boolean ok = work.run();
            con.commit();
            return ok;
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(prev);
        }
    }

    @FunctionalInterface
    private interface TxWork {
        boolean run() throws SQLException;
    }
}
