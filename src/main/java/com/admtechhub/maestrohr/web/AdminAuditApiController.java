package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.platform.AdminAuditQueries;
import com.admtechhub.maestrohr.platform.AdminAuditView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAuditApiController {

    private final AdminAuditQueries queries;

    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Page<AdminAuditView>>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {

        UUID tenantUuid = (tenantId != null && !tenantId.isBlank())
                ? UUID.fromString(tenantId) : null;
        Integer statusMin = null, statusMax = null;
        if (statusGroup != null) {
            switch (statusGroup) {
                case "2xx": statusMin = 200; statusMax = 300; break;
                case "3xx": statusMin = 300; statusMax = 400; break;
                case "4xx": statusMin = 400; statusMax = 500; break;
                case "5xx": statusMin = 500; statusMax = 600; break;
            }
        }

        long total = queries.countAuditLogs(tenantUuid, actor, action, entityType,
                statusMin, statusMax, dateFrom, dateTo);
        List<AdminAuditView> rows = queries.findAuditLogs(tenantUuid, actor, action, entityType,
                statusMin, statusMax, dateFrom, dateTo, size, page * size);

        PageRequest pageable = PageRequest.of(page, size);
        Page<AdminAuditView> pageResult = new PageImpl<>(rows, pageable, total);
        return ResponseEntity.ok(ApiResponse.success("Audit logs retrieved", pageResult));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFilterOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("actions", queries.distinctActionsLast6Months());
        options.put("entityTypes", queries.distinctEntityTypesLast6Months());
        options.put("tenants", queries.allTenants().stream()
                .map(arr -> Map.of("id", arr[0].toString(), "name", arr[1]))
                .toList());
        return ResponseEntity.ok(ApiResponse.success("Filter options", options));
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> exportCsv(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {

        UUID tenantUuid = (tenantId != null && !tenantId.isBlank())
                ? UUID.fromString(tenantId) : null;
        Integer statusMin = null, statusMax = null;
        if (statusGroup != null) {
            switch (statusGroup) {
                case "2xx": statusMin = 200; statusMax = 300; break;
                case "3xx": statusMin = 300; statusMax = 400; break;
                case "4xx": statusMin = 400; statusMax = 500; break;
                case "5xx": statusMin = 500; statusMax = 600; break;
            }
        }
        // No pagination — fetch all matching rows
        List<AdminAuditView> rows = queries.findAuditLogs(tenantUuid, actor, action, entityType,
                statusMin, statusMax, dateFrom, dateTo, Integer.MAX_VALUE, 0);

        StreamingResponseBody stream = outputStream -> {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.println("Tenant,Company,Actor,Action,Entity Type,Entity ID,Path,Method,Status,Details,Impersonated By,Timestamp");
            DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            for (AdminAuditView r : rows) {
                writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%d,\"%s\",%s,%s%n",
                        r.tenantId(),
                        r.companyName() != null ? r.companyName() : "",
                        r.actorEmail(),
                        r.action(),
                        r.entityType(),
                        r.entityId(),
                        r.requestPath(),
                        r.httpMethod(),
                        r.statusCode(),
                        r.details() != null ? r.details().replace("\"", "\"\"") : "",
                        r.impersonatedBy() != null ? r.impersonatedBy() : "",
                        r.createdAt() != null ? r.createdAt().format(fmt) : "");
            }
            writer.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_logs.csv")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(stream);
    }
}