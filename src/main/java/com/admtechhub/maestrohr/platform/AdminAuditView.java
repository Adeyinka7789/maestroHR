package com.admtechhub.maestrohr.platform;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Projection for one row of the super-admin audit log table.
 * Includes the tenant's company name (from the LEFT JOIN) so we can display
 * cross-tenant data without a second query.
 */
public record AdminAuditView(
        UUID tenantId,
        String companyName,
        String actorEmail,
        String action,
        String entityType,
        String entityId,
        String requestPath,
        String httpMethod,
        int statusCode,
        String details,
        String impersonatedBy,
        OffsetDateTime createdAt
) {}