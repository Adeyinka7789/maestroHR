package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for {@link AttendancePolicy}. Mirrors {@link com.admtechhub.maestrohr.loan.LoanPolicyService}'s
 * shape. Resolution (which policy applies to a given employee) is NOT here — that already lives in
 * {@link AttendanceService#getEffectivePolicy}, used by {@link com.admtechhub.maestrohr.payroll.PayrollEngine}.
 * This service only manages the policy rows themselves.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendancePolicyService {

    private final AttendancePolicyRepository policyRepository;
    private final TenantRepository tenantRepository;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public AttendancePolicy createPolicy(AttendancePolicyRequest req) {
        validateConversionSettings(req.getLateToAbsenceConversionEnabled(), req.getLateToAbsenceConversionCount());

        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));

        AttendancePolicy policy = AttendancePolicy.builder()
                .tenant(tenant)
                .name(req.getName().trim())
                .description(req.getDescription())
                .gracePeriodMinutes(req.getGracePeriodMinutes() != null ? req.getGracePeriodMinutes() : 0)
                .lateDeductionType(req.getLateDeductionType() != null ? req.getLateDeductionType() : DeductionType.FLAT)
                .lateDeductionValue(req.getLateDeductionValue() != null ? req.getLateDeductionValue() : BigDecimal.ZERO)
                .lateFreeCount(req.getLateFreeCount() != null ? req.getLateFreeCount() : 0)
                .absenceDeductionType(req.getAbsenceDeductionType() != null ? req.getAbsenceDeductionType() : DeductionType.FLAT)
                .absenceDeductionValue(req.getAbsenceDeductionValue() != null ? req.getAbsenceDeductionValue() : BigDecimal.ZERO)
                .lateToAbsenceConversionEnabled(Boolean.TRUE.equals(req.getLateToAbsenceConversionEnabled()))
                .lateToAbsenceConversionCount(req.getLateToAbsenceConversionCount())
                .isActive(req.getIsActive() == null || req.getIsActive())
                .build();

        AttendancePolicy saved = policyRepository.save(policy);
        log.info("AttendancePolicy {} created for tenant {}", saved.getId(), tenantId);
        return saved;
    }

    @Transactional
    public AttendancePolicy updatePolicy(UUID policyId, AttendancePolicyRequest req) {
        AttendancePolicy policy = requirePolicy(policyId);

        Boolean conversionEnabled = req.getLateToAbsenceConversionEnabled() != null
                ? req.getLateToAbsenceConversionEnabled() : policy.getLateToAbsenceConversionEnabled();
        Integer conversionCount = req.getLateToAbsenceConversionCount() != null
                ? req.getLateToAbsenceConversionCount() : policy.getLateToAbsenceConversionCount();
        validateConversionSettings(conversionEnabled, conversionCount);

        if (req.getName() != null) policy.setName(req.getName().trim());
        if (req.getDescription() != null) policy.setDescription(req.getDescription());
        if (req.getGracePeriodMinutes() != null) policy.setGracePeriodMinutes(req.getGracePeriodMinutes());
        if (req.getLateDeductionType() != null) policy.setLateDeductionType(req.getLateDeductionType());
        if (req.getLateDeductionValue() != null) policy.setLateDeductionValue(req.getLateDeductionValue());
        if (req.getLateFreeCount() != null) policy.setLateFreeCount(req.getLateFreeCount());
        if (req.getAbsenceDeductionType() != null) policy.setAbsenceDeductionType(req.getAbsenceDeductionType());
        if (req.getAbsenceDeductionValue() != null) policy.setAbsenceDeductionValue(req.getAbsenceDeductionValue());
        if (req.getLateToAbsenceConversionEnabled() != null) policy.setLateToAbsenceConversionEnabled(req.getLateToAbsenceConversionEnabled());
        policy.setLateToAbsenceConversionCount(req.getLateToAbsenceConversionCount());
        if (req.getIsActive() != null) policy.setIsActive(req.getIsActive());
        return policyRepository.save(policy);
    }

    @Transactional
    public void deletePolicy(UUID policyId) {
        AttendancePolicy policy = requirePolicy(policyId);
        policy.setDeletedAt(OffsetDateTime.now());
        policy.setIsActive(false);
        policyRepository.save(policy);
        log.info("AttendancePolicy {} soft-deleted", policyId);
    }

    @Transactional(readOnly = true)
    public List<AttendancePolicy> getPoliciesForTenant() {
        return policyRepository.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public AttendancePolicy getPolicy(UUID policyId) {
        return requirePolicy(policyId);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Closes the Stage 1 gap: a policy with conversion enabled but no (or non-positive) count
     * would silently never convert anything in {@code PayrollEngine.calculateLateDeduction}
     * (its {@code lateToAbsenceConversionCount > 0} guard would just fall through to
     * "no conversion"). Rejecting it here instead surfaces the misconfiguration immediately.
     */
    private void validateConversionSettings(Boolean enabled, Integer count) {
        if (Boolean.TRUE.equals(enabled) && (count == null || count <= 0)) {
            throw new IllegalArgumentException(
                    "Late-to-absence conversion count must be greater than zero when conversion is enabled.");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AttendancePolicy requirePolicy(UUID id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Attendance policy not found: " + id));
    }
}
