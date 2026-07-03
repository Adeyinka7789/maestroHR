package com.admtechhub.maestrohr.retirement;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + resolution for {@link RetirementPolicy}, the tenant's single retirement
 * settings row. {@link #getEstimatedRetirementDate} is the single source of truth for
 * the retirement-date calculation — every display location must call it rather than
 * reimplementing the {@code dateOfBirth.plusYears(retirementAge)} math.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetirementPolicyService {

    private static final int DEFAULT_RETIREMENT_AGE = 60;
    private static final String DEFAULT_NOTIFICATION_THRESHOLD_DAYS = "180,30";

    private final RetirementPolicyRepository policyRepository;
    private final TenantRepository tenantRepository;

    /**
     * Returns the tenant's retirement policy, creating one with defaults (age 60, "180,30")
     * if none exists yet. Uses REQUIRES_NEW (mirrors {@code PaymentService.getOrCreatePendingInvoice})
     * so the create-on-read path isn't blocked by a caller's read-only transaction (e.g. the
     * reporting/detail-page callers of {@link #getEstimatedRetirementDate}). Note this only
     * takes effect when called through the Spring proxy (i.e. from another bean) — the
     * self-invocation from {@link #getEstimatedRetirementDate} below relies on that method's
     * own REQUIRES_NEW instead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetirementPolicy getOrCreateDefault(UUID tenantId) {
        return policyRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefault(tenantId));
    }

    @Transactional
    public RetirementPolicy update(UUID tenantId, RetirementPolicyRequest req) {
        RetirementPolicy policy = getOrCreateDefault(tenantId);

        if (req.getRetirementAge() != null) {
            if (req.getRetirementAge() <= 0) {
                throw new IllegalArgumentException("Retirement age must be a positive integer.");
            }
            policy.setRetirementAge(req.getRetirementAge());
        }
        if (req.getNotificationThresholdDaysRaw() != null) {
            List<Integer> parsed = parseThresholds(req.getNotificationThresholdDaysRaw());
            policy.setNotificationThresholdDays(join(parsed));
        }
        return policyRepository.save(policy);
    }

    /** Parses a comma-separated list of positive integers, sorted descending; throws on malformed input. */
    public List<Integer> parseThresholds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Notification threshold days must not be blank.");
        }
        List<Integer> thresholds = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                        "Notification threshold days must be a comma-separated list of positive integers, e.g. \"180,30\".");
            }
            int value;
            try {
                value = Integer.parseInt(trimmed);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Notification threshold days must be a comma-separated list of positive integers, e.g. \"180,30\". Invalid value: \"" + trimmed + "\".");
            }
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "Notification threshold days must each be greater than zero. Invalid value: " + value + ".");
            }
            thresholds.add(value);
        }
        thresholds.sort(Comparator.reverseOrder());
        return thresholds;
    }

    /**
     * Resolves the estimated retirement date for an employee: the tenant's policy
     * retirement age applied to the employee's date of birth. Empty when the employee
     * has no date of birth on file.
     *
     * <p>REQUIRES_NEW so callers running in a read-only transaction (the employee-detail
     * and payslip render paths) don't block the create-on-read default policy insert.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<LocalDate> getEstimatedRetirementDate(Employee employee) {
        if (employee == null || employee.getDateOfBirth() == null || employee.getTenant() == null) {
            return Optional.empty();
        }
        RetirementPolicy policy = getOrCreateDefault(employee.getTenant().getId());
        return Optional.of(employee.getDateOfBirth().plusYears(policy.getRetirementAge()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RetirementPolicy createDefault(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));
        RetirementPolicy policy = RetirementPolicy.builder()
                .tenant(tenant)
                .retirementAge(DEFAULT_RETIREMENT_AGE)
                .notificationThresholdDays(DEFAULT_NOTIFICATION_THRESHOLD_DAYS)
                .build();
        RetirementPolicy saved = policyRepository.save(policy);
        log.info("RetirementPolicy {} created with defaults for tenant {}", saved.getId(), tenantId);
        return saved;
    }

    private String join(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
