package com.admtechhub.maestrohr.performance;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceReviewService {

    private final ReviewTemplateRepository reviewTemplateRepository;
    private final ReviewCycleRepository reviewCycleRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    // ==================== TEMPLATE METHODS ====================

    @Transactional
    public ReviewTemplate createTemplate(ReviewTemplate template) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        template.setTenant(tenant);
        template.setStatus(ReviewTemplate.TemplateStatus.ACTIVE);
        return reviewTemplateRepository.save(template);
    }

    @Transactional(readOnly = true)
    public List<ReviewTemplate> getAllTemplates() {
        UUID tenantId = getCurrentTenantId();
        return reviewTemplateRepository.findAllByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<ReviewTemplate> getActiveTemplates() {
        UUID tenantId = getCurrentTenantId();
        return reviewTemplateRepository.findActiveByTenantId(tenantId);
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        reviewTemplateRepository.deleteById(id);
    }

    // ==================== DASHBOARD STATS ====================

    @Transactional(readOnly = true)
    public Page<ReviewCycle> getReviewCycles(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        return reviewCycleRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardStats() {
        UUID tenantId = getCurrentTenantId();
        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingReviews", reviewCycleRepository.countPendingByTenantId(tenantId));
        stats.put("completedReviews", reviewCycleRepository.countCompletedByTenantId(tenantId));
        stats.put("overdueReviews", reviewCycleRepository.countOverdueByTenantId(tenantId));
        Double avgRating = reviewCycleRepository.getAverageRatingByTenantId(tenantId);
        stats.put("averageRating", avgRating != null ? Math.round(avgRating * 10) / 10.0 : 0.0);
        return stats;
    }
}