package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase E4b — privileged reads for the device authentication bootstrap path.
 *
 * <p>Device API key lookup runs before a tenant session exists: the key hash itself
 * is the credential that reveals which tenant the device belongs to. Under the primary
 * RLS-bound datasource this query would return nothing (no {@code app.current_tenant}
 * is set yet). Routed through the privileged datasource it resolves across all tenants.
 *
 * <p>Reads only. The matching {@code last_used_at} update is a low-stakes best-effort
 * write also issued via the privileged datasource so it doesn't need a tenant session.
 */
@Repository
public class DeviceBootstrapQueries {

    private final JdbcTemplate jdbc;

    public DeviceBootstrapQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lookup an active device by its SHA-256 API key hash, across all tenants. */
    public Optional<DeviceKeyRow> findByApiKeyHash(String sha256Hash) {
        List<DeviceKeyRow> rows = jdbc.query(
                "SELECT id, tenant_id, device_name FROM device_api_keys "
                        + "WHERE api_key_hash = ? AND is_active = true",
                (rs, n) -> new DeviceKeyRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("device_name")),
                sha256Hash);
        return rows.stream().findFirst();
    }

    /** Best-effort timestamp update — uses the privileged datasource so no tenant session is needed. */
    public void touchLastUsedAt(UUID deviceId) {
        jdbc.update("UPDATE device_api_keys SET last_used_at = now() WHERE id = ?", deviceId);
    }

    public record DeviceKeyRow(UUID id, UUID tenantId, String deviceName) {
    }
}
