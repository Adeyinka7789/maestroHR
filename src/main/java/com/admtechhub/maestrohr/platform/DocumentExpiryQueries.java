package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Privileged cross-tenant scan for the daily document-expiry alert (see {@code DocumentExpiryAlertJob}).
 *
 * <p>The scheduler runs with no tenant session, so under the RLS-enforced {@code maestro_app}
 * role a tenant-scoped query would return nothing. This scan goes through the privileged
 * datasource so it sees every tenant's documents; the job then binds each tenant's session
 * individually before reading its HR users and writing notifications.
 */
@Repository
public class DocumentExpiryQueries {

    private final JdbcTemplate jdbc;

    public DocumentExpiryQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A document whose expiry falls on one of the alert horizons, with its owning tenant. */
    public record ExpiringDocumentRow(UUID tenantId, UUID employeeId, String fileName,
                                      String documentType, LocalDate expiryDate) {
    }

    /**
     * Documents across all tenants whose {@code expiry_date} is exactly one of the supplied
     * horizon dates (e.g. today+30 and today+7). Ordered by tenant so the caller can process
     * one tenant's alerts together.
     */
    public List<ExpiringDocumentRow> findExpiringOn(List<LocalDate> horizonDates) {
        if (horizonDates == null || horizonDates.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", horizonDates.stream().map(d -> "?").toList());
        Object[] params = horizonDates.stream().map(java.sql.Date::valueOf).toArray();
        return jdbc.query(
                "SELECT tenant_id, employee_id, file_name, document_type, expiry_date "
                        + "FROM employee_documents WHERE expiry_date IN (" + placeholders + ") "
                        + "ORDER BY tenant_id, expiry_date",
                (rs, n) -> new ExpiringDocumentRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("document_type"),
                        rs.getObject("expiry_date", LocalDate.class)),
                params);
    }
}
