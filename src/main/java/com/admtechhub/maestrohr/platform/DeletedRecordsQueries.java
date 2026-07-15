package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Privileged backing for the super-admin trash page ({@code /htmx/admin/deleted-records}). The
 * listing spans every tenant's soft-deleted departments, employees and pay_grades in one UNION,
 * joined to {@code tenants} for the company name; restore and purge-now act on a single row. All of
 * it runs through the privileged ({@code postgres}) datasource because the super-admin carries their
 * own tenant in session — under the RLS-enforced {@code maestro_app} primary these rows would be
 * invisible (the {@code @SQLRestriction} also hides them as trashed). Reads and the two small writes
 * are bundled here because they are one cohesive feature; the scheduled bulk purge lives separately
 * in {@link SoftDeleteCleanupQueries}.
 */
@Repository
public class DeletedRecordsQueries {

    /** Retention window mirrored from {@code SoftDeleteCleanupJob}, used to compute days-remaining. */
    private static final int RETENTION_DAYS = 90;

    private final JdbcTemplate jdbc;

    public DeletedRecordsQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The soft-deletable kinds, each mapping a URL token to its table and audit entity name. */
    public enum DeletedType {
        DEPARTMENT("departments", "department"),
        EMPLOYEE("employees", "employee"),
        PAY_GRADE("pay_grades", "pay_grade"),
        // A whole company trashed via self-service deletion (V55). Its "tenant id" IS its own id;
        // restore reactivates the tenant and its users, and purge cascades to all tenant-scoped data.
        TENANT("tenants", "tenant");

        private final String table;
        private final String auditEntity;

        DeletedType(String table, String auditEntity) {
            this.table = table;
            this.auditEntity = auditEntity;
        }

        public String auditEntity() {
            return auditEntity;
        }
    }

    /**
     * Every soft-deleted row across all tenants, newest-trashed first. {@code daysRemaining} is the
     * retention window minus whole days elapsed since {@code deleted_at}, floored at 0.
     */
    public List<DeletedRecordRow> findAllDeleted() {
        return jdbc.query(
                "SELECT x.type, x.id, x.label, t.company_name AS tenant_name, x.deleted_at, "
                        + "  GREATEST(0, ? - FLOOR(EXTRACT(EPOCH FROM (now() - x.deleted_at)) / 86400))::int AS days_remaining "
                        + "FROM ( "
                        + "    SELECT 'DEPARTMENT' AS type, d.id, d.name AS label, d.tenant_id, d.deleted_at "
                        + "    FROM departments d WHERE d.deleted_at IS NOT NULL "
                        + "    UNION ALL "
                        + "    SELECT 'EMPLOYEE', e.id, e.first_name || ' ' || e.last_name, e.tenant_id, e.deleted_at "
                        + "    FROM employees e WHERE e.deleted_at IS NOT NULL "
                        + "    UNION ALL "
                        + "    SELECT 'PAY_GRADE', p.id, p.name, p.tenant_id, p.deleted_at "
                        + "    FROM pay_grades p WHERE p.deleted_at IS NOT NULL "
                        + "    UNION ALL "
                        + "    SELECT 'TENANT', tn.id, tn.company_name, tn.id, tn.deleted_at "
                        + "    FROM tenants tn WHERE tn.deleted_at IS NOT NULL "
                        + ") x "
                        + "JOIN tenants t ON t.id = x.tenant_id "
                        + "ORDER BY x.deleted_at DESC",
                (rs, n) -> new DeletedRecordRow(
                        rs.getString("type"),
                        rs.getObject("id", UUID.class),
                        rs.getString("label"),
                        rs.getString("tenant_name"),
                        rs.getObject("deleted_at", OffsetDateTime.class),
                        rs.getLong("days_remaining")),
                RETENTION_DAYS);
    }

    /**
     * Restore a trashed row by clearing {@code deleted_at}. For an employee the linked login is
     * reactivated in the same transaction (soft-delete had deactivated it). Returns the row's tenant
     * id (for the audit entry) or {@code null} if no matching trashed row exists.
     */
    public UUID restore(DeletedType type, UUID id) {
        UUID tenantId = tenantIdOfTrashed(type, id);
        if (tenantId == null) {
            return null;
        }
        if (type == DeletedType.EMPLOYEE) {
            jdbc.execute((Connection con) -> inTransaction(con, () -> {
                update(con, "UPDATE employees SET deleted_at = NULL WHERE id = ?", id);
                // Reactivate the linked login, if any (soft-delete had set it inactive).
                update(con, "UPDATE users u SET is_active = true "
                        + "FROM employees e WHERE e.id = ? AND u.id = e.user_id", id);
                return true;
            }));
        } else if (type == DeletedType.TENANT) {
            // Un-trash the company and reactivate the tenant + every user under it (self-service
            // deletion had deactivated them), so its owners can sign back in.
            jdbc.execute((Connection con) -> inTransaction(con, () -> {
                update(con, "UPDATE tenants SET deleted_at = NULL, is_active = true WHERE id = ?", id);
                update(con, "UPDATE users SET is_active = true WHERE tenant_id = ?", id);
                return true;
            }));
        } else {
            jdbc.update("UPDATE " + type.table + " SET deleted_at = NULL WHERE id = ?", id);
        }
        return tenantId;
    }

    /**
     * Permanently delete a trashed row right now, bypassing the retention wait. Returns the row's
     * tenant id, or {@code null} if no matching trashed row exists. A foreign-key violation (the row
     * is still referenced) propagates as a {@code DataAccessException} for the caller to surface.
     */
    public UUID purgeNow(DeletedType type, UUID id) {
        UUID tenantId = tenantIdOfTrashed(type, id);
        if (tenantId == null) {
            return null;
        }
        jdbc.update("DELETE FROM " + type.table + " WHERE id = ? AND deleted_at IS NOT NULL", id);
        return tenantId;
    }

    /**
     * Tenant id of a trashed row, or null when the id is unknown or the row is not trashed. The
     * {@code tenants} table has no {@code tenant_id} column — its own {@code id} is the tenant id —
     * so the projected column is chosen per type.
     */
    private UUID tenantIdOfTrashed(DeletedType type, UUID id) {
        String idColumn = type == DeletedType.TENANT ? "id" : "tenant_id";
        List<UUID> ids = jdbc.query(
                "SELECT " + idColumn + " AS tid FROM " + type.table + " WHERE id = ? AND deleted_at IS NOT NULL",
                (rs, n) -> rs.getObject("tid", UUID.class), id);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void update(Connection con, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    private boolean inTransaction(Connection con, TxWork work) throws SQLException {
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

    /** One row of the super-admin trash page. */
    public record DeletedRecordRow(
            String type,            // DEPARTMENT | EMPLOYEE | PAY_GRADE
            UUID id,
            String label,           // department / grade name, or employee full name
            String tenantName,      // owning company
            OffsetDateTime deletedAt,
            long daysRemaining) {   // retention days minus days elapsed, floored at 0
    }
}
