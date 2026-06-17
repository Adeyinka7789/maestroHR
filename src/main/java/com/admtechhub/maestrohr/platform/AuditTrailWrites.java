package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Phase E4c-ii — privileged INSERT for audit_trail.
 *
 * audit_trail has an RLS WITH CHECK policy. During login (and any request before
 * TenantContext is populated) app.current_tenant is not set, so inserts through the
 * primary maestro_app connection fail the policy and are rejected. Routing through the
 * privileged (postgres) datasource bypasses RLS and guarantees audit records always land,
 * regardless of tenant-session state.
 */
@Repository
public class AuditTrailWrites {

    private final JdbcTemplate jdbc;

    public AuditTrailWrites(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID tenantId, String actorEmail, String action, String entityType,
                       String entityId, String requestPath, String httpMethod,
                       String ipAddress, int statusCode, String details) {
        jdbc.update(
                "INSERT INTO audit_trail " +
                "(id, tenant_id, actor_email, action, entity_type, entity_id, " +
                " request_path, http_method, ip_address, status_code, details, " +
                " created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                tenantId, actorEmail, action, entityType, entityId,
                requestPath, httpMethod, ipAddress, statusCode, details);
    }
}
