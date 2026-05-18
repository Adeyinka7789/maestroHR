package com.admtechhub.maestrohr.performance;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
@Slf4j
public class PerformanceReviewController {

    private final PerformanceReviewService performanceReviewService;

    // ==================== TEMPLATE ENDPOINTS ====================

    @GetMapping("/templates")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<List<ReviewTemplate>>> getTemplates() {
        List<ReviewTemplate> templates = performanceReviewService.getAllTemplates();
        return ResponseEntity.ok(ApiResponse.success("Templates retrieved", templates));
    }

    @GetMapping("/templates/active")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<List<ReviewTemplate>>> getActiveTemplates() {
        List<ReviewTemplate> templates = performanceReviewService.getActiveTemplates();
        return ResponseEntity.ok(ApiResponse.success("Active templates retrieved", templates));
    }

    @PostMapping("/templates")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ReviewTemplate>> createTemplate(@RequestBody ReviewTemplate template) {
        ReviewTemplate created = performanceReviewService.createTemplate(template);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Template created", created));
    }

    @DeleteMapping("/templates/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable UUID id) {
        performanceReviewService.deleteTemplate(id);
        return ResponseEntity.ok(ApiResponse.success("Template deleted", null));
    }

    // ==================== DASHBOARD ENDPOINTS ====================

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        Map<String, Object> stats = performanceReviewService.getDashboardStats();
        return ResponseEntity.ok(ApiResponse.success("Stats retrieved", stats));
    }

    // ==================== REVIEW CYCLE ENDPOINTS ====================

    @GetMapping("/cycles")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Page<ReviewCycle>>> getReviewCycles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ReviewCycle> cycles = performanceReviewService.getReviewCycles(pageable);

        // Enhance with employee and template names
        cycles.getContent().forEach(cycle -> {
            if (cycle.getEmployee() != null) {
                cycle.getEmployee().getFullName();
            }
            if (cycle.getTemplate() != null) {
                cycle.getTemplate().getName();
            }
        });

        return ResponseEntity.ok(ApiResponse.success("Review cycles retrieved", cycles));
    }
}