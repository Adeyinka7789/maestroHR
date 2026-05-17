package com.admtechhub.maestrohr.audit;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditTrailController {

    private final AuditTrailService auditTrailService;

    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditTrail>>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate) {

        // Set default date range (last 30 days if not specified)
        OffsetDateTime from = startDate != null ? startDate : OffsetDateTime.now().minusDays(30);
        OffsetDateTime to = endDate != null ? endDate : OffsetDateTime.now();

        // Get all logs in the date range
        List<AuditTrail> allLogs = auditTrailService.findBetween(from, to);

        // Apply filters if provided
        List<AuditTrail> filteredLogs = allLogs.stream()
                .filter(log -> action == null || action.isEmpty() || (log.getAction() != null && log.getAction().contains(action)))
                .filter(log -> entityType == null || entityType.isEmpty() || (log.getEntityType() != null && log.getEntityType().equals(entityType)))
                .filter(log -> actor == null || actor.isEmpty() || (log.getActorEmail() != null && log.getActorEmail().toLowerCase().contains(actor.toLowerCase())))
                .collect(Collectors.toList());

        // Apply pagination
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredLogs.size());

        List<AuditTrail> pageContent = filteredLogs.subList(start, end);
        Page<AuditTrail> pageResult = new PageImpl<>(pageContent, pageable, filteredLogs.size());

        return ResponseEntity.ok(ApiResponse.success("Audit logs retrieved", pageResult));
    }

    @GetMapping("/filter-options")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, List<String>>>> getFilterOptions() {
        Map<String, List<String>> options = new HashMap<>();

        // Get logs from last 6 months for filter options
        List<AuditTrail> recentLogs = auditTrailService.findBetween(OffsetDateTime.now().minusMonths(6), OffsetDateTime.now());

        List<String> actions = recentLogs.stream()
                .map(AuditTrail::getAction)
                .filter(a -> a != null && !a.isEmpty())
                .distinct()
                .limit(50)
                .collect(Collectors.toList());

        List<String> entityTypes = recentLogs.stream()
                .map(AuditTrail::getEntityType)
                .filter(t -> t != null && !t.isEmpty())
                .distinct()
                .limit(20)
                .collect(Collectors.toList());

        options.put("actions", actions);
        options.put("entityTypes", entityTypes);

        return ResponseEntity.ok(ApiResponse.success("Filter options retrieved", options));
    }
}