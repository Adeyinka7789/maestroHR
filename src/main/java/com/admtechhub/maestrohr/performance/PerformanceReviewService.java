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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        return odt != null ? odt.toLocalDateTime() : null;
    }

    // ==================== DTO CONVERSIONS ====================

    private ReviewTemplateDTO toDto(ReviewTemplate template) {
        return ReviewTemplateDTO.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .reviewType(template.getReviewType() != null ? template.getReviewType().name() : null)
                .status(template.getStatus() != null ? template.getStatus().name() : null)
                .build();
    }

    private ReviewCycleDTO toDto(ReviewCycle cycle) {
        return ReviewCycleDTO.builder()
                .id(cycle.getId())
                .employeeId(cycle.getEmployee().getId())
                .employeeName(cycle.getEmployee().getFullName())
                .employeeNumber(cycle.getEmployee().getEmployeeNumber())
                .reviewerId(cycle.getReviewer().getId())
                .reviewerName(cycle.getReviewer().getFullName())
                .templateId(cycle.getTemplate().getId())
                .templateName(cycle.getTemplate().getName())
                .periodStart(cycle.getPeriodStart())
                .periodEnd(cycle.getPeriodEnd())
                .dueDate(cycle.getDueDate())
                .status(cycle.getStatus())
                .selfReviewStatus(cycle.getSelfReviewStatus())
                .managerReviewStatus(cycle.getManagerReviewStatus())
                .overallRating(cycle.getOverallRating())
                .createdBy(cycle.getCreatedBy())
                .createdAt(toLocalDateTime(cycle.getCreatedAt()))
                .build();
    }

    // ==================== TEMPLATE METHODS ====================

    @Transactional
    public ReviewTemplateDTO createTemplate(ReviewTemplate template) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        template.setTenant(tenant);
        template.setStatus(ReviewTemplate.TemplateStatus.ACTIVE);
        ReviewTemplate saved = reviewTemplateRepository.save(template);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ReviewTemplateDTO> getAllTemplates() {
        UUID tenantId = getCurrentTenantId();
        List<ReviewTemplate> templates = reviewTemplateRepository.findAllByTenantId(tenantId);
        return templates.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ReviewTemplateDTO> getActiveTemplates() {
        UUID tenantId = getCurrentTenantId();
        List<ReviewTemplate> templates = reviewTemplateRepository.findActiveByTenantId(tenantId);
        return templates.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        reviewTemplateRepository.deleteById(id);
    }

    // ==================== REVIEW CYCLE METHODS ====================

    @Transactional(readOnly = true)
    public Page<ReviewCycleDTO> getReviewCycles(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        Page<ReviewCycle> page = reviewCycleRepository.findByTenantId(tenantId, pageable);
        return page.map(this::toDto);
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