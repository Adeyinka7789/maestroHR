package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Privileged cross-tenant audit log queries for the super-admin audit console.
 * All queries span every tenant using the postgres-privileged JDBC template,
 * bypassing RLS that would normally restrict to the current tenant.
 */
@Repository
public class AdminAuditQueries {

    private final JdbcTemplate jdbc;

    public AdminAuditQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Dynamic, parameterised audit-log search with all filters applied in SQL.
     * Returns a paginated slice ordered by created_at DESC.
     */
    public List<AdminAuditView> findAuditLogs(UUID tenantId, String actorEmail, String action,
                                              String entityType, Integer statusMin, Integer statusMax,
                                              String dateFrom, String dateTo,
                                              int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT at.*, t.company_name " +
                        "FROM audit_trail at " +
                        "LEFT JOIN tenants t ON t.id = at.tenant_id " +
                        "WHERE 1=1 ");

        // Filter placeholders will be added dynamically
        // (tenantId, actorEmail, action, entityType, statusMin/Max, dateFrom/To)
        // We'll build the WHERE clause and collect parameters in order.

        // Using a helper to keep order.
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (tenantId != null) {
            sql.append("AND at.tenant_id = ? ");
            params.add(tenantId);
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            sql.append("AND at.actor_email ILIKE ? ");
            params.add("%" + actorEmail + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append("AND at.action ILIKE ? ");
            params.add("%" + action + "%");
        }
        if (entityType != null && !entityType.isBlank()) {
            sql.append("AND at.entity_type = ? ");
            params.add(entityType);
        }
        if (statusMin != null) {
            sql.append("AND at.status_code >= ? ");
            params.add(statusMin);
        }
        if (statusMax != null) {
            sql.append("AND at.status_code < ? ");
            params.add(statusMax);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append("AND at.created_at >= ? ");
            params.add(java.time.OffsetDateTime.parse(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append("AND at.created_at <= ? ");
            params.add(java.time.OffsetDateTime.parse(dateTo));
        }

        sql.append("ORDER BY at.created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AdminAuditView(
                rs.getObject("tenant_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("request_path"),
                rs.getString("http_method"),
                rs.getInt("status_code"),
                rs.getString("details"),
                rs.getString("impersonated_by"),
                rs.getObject("created_at", java.time.OffsetDateTime.class)
        ), params.toArray());
    }

    /** Total count matching the same filters (for pagination). */
    public long countAuditLogs(UUID tenantId, String actorEmail, String action,
                               String entityType, Integer statusMin, Integer statusMax,
                               String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM audit_trail at WHERE 1=1 ");
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        if (tenantId != null) {
            sql.append("AND at.tenant_id = ? ");
            params.add(tenantId);
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            sql.append("AND at.actor_email ILIKE ? ");
            params.add("%" + actorEmail + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append("AND at.action ILIKE ? ");
            params.add("%" + action + "%");
        }
        if (entityType != null && !entityType.isBlank()) {
            sql.append("AND at.entity_type = ? ");
            params.add(entityType);
        }
        if (statusMin != null) {
            sql.append("AND at.status_code >= ? ");
            params.add(statusMin);
        }
        if (statusMax != null) {
            sql.append("AND at.status_code < ? ");
            params.add(statusMax);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append("AND at.created_at >= ? ");
            params.add(java.time.OffsetDateTime.parse(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append("AND at.created_at <= ? ");
            params.add(java.time.OffsetDateTime.parse(dateTo));
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    /** Distinct actions across the last 6 months, for the filter dropdown. */
    public List<String> distinctActionsLast6Months() {
        return jdbc.queryForList(
                "SELECT DISTINCT action FROM audit_trail " +
                        "WHERE created_at >= ? " +
                        "ORDER BY action",
                String.class,
                java.time.OffsetDateTime.now().minusMonths(6));
    }

    /** Distinct entity types across the last 6 months. */
    public List<String> distinctEntityTypesLast6Months() {
        return jdbc.queryForList(
                "SELECT DISTINCT entity_type FROM audit_trail " +
                        "WHERE created_at >= ? " +
                        "ORDER BY entity_type",
                String.class,
                java.time.OffsetDateTime.now().minusMonths(6));
    }

    /** All tenants for the dropdown (id + company name). */
    public List<Object[]> allTenants() {
        return jdbc.query(
                "SELECT id, company_name FROM tenants ORDER BY company_name",
                (rs, n) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("company_name")
                });
    }
}