package com.admtechhub.maestrohr.platform;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Assembles the model for the admin audit page (pagination, filter options).
 */
@Service
public class AdminAuditService {

    private final AdminAuditQueries queries;

    public AdminAuditService(AdminAuditQueries queries) {
        this.queries = queries;
    }

    public AdminAuditPage buildPage(String tenantIdStr, String actorEmail, String action,
                                    String entityType, String statusGroup,
                                    String dateFrom, String dateTo,
                                    int page, int size) {

        UUID tenantId = (tenantIdStr != null && !tenantIdStr.isBlank())
                ? UUID.fromString(tenantIdStr) : null;

        // Map status group to min/max range
        Integer statusMin = null, statusMax = null;
        if (statusGroup != null) {
            switch (statusGroup) {
                case "2xx": statusMin = 200; statusMax = 300; break;
                case "3xx": statusMin = 300; statusMax = 400; break;
                case "4xx": statusMin = 400; statusMax = 500; break;
                case "5xx": statusMin = 500; statusMax = 600; break;
            }
        }

        long total = queries.countAuditLogs(tenantId, actorEmail, action, entityType,
                statusMin, statusMax, dateFrom, dateTo);
        int totalPages = (int) Math.ceil((double) total / size);
        if (page < 0) page = 0;
        if (page >= totalPages && totalPages > 0) page = totalPages - 1;

        var rows = queries.findAuditLogs(tenantId, actorEmail, action, entityType,
                statusMin, statusMax, dateFrom, dateTo, size, page * size);

        return new AdminAuditPage(
                rows,
                queries.distinctActionsLast6Months(),
                queries.distinctEntityTypesLast6Months(),
                queries.allTenants().stream()
                        .map(arr -> new TenantOption((UUID) arr[0], (String) arr[1]))
                        .toList(),
                page, totalPages, total
        );
    }

    public record AdminAuditPage(
            java.util.List<AdminAuditView> rows,
            java.util.List<String> actions,
            java.util.List<String> entityTypes,
            java.util.List<TenantOption> tenants,
            int currentPage,
            int totalPages,
            long totalCount
    ) {}

    public record TenantOption(UUID id, String companyName) {}
}