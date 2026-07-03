package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanPolicyService {

    private final LoanPolicyRepository policyRepository;
    private final EmployeeLoanRepository loanRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final PlatformSettingsService platformSettingsService;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public LoanPolicy createPolicy(LoanPolicyRequest req) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));

        LoanPolicy policy = LoanPolicy.builder()
                .tenant(tenant)
                .name(req.getName().trim())
                .description(req.getDescription())
                .minAmountKobo(req.getMinAmountKobo() != null ? req.getMinAmountKobo() : 0L)
                .maxAmountKobo(req.getMaxAmountKobo())
                .maxMultiplierOfGross(req.getMaxMultiplierOfGross() != null ? req.getMaxMultiplierOfGross() : new BigDecimal("3.0"))
                .maxDeductionPct(req.getMaxDeductionPct() != null ? req.getMaxDeductionPct() : new BigDecimal("33.0"))
                .netFloorPct(req.getNetFloorPct() != null ? req.getNetFloorPct() : new BigDecimal("50.0"))
                .maxTenorMonths(req.getMaxTenorMonths() != null ? req.getMaxTenorMonths() : 12)
                .interestRatePct(req.getInterestRatePct() != null ? req.getInterestRatePct() : BigDecimal.ZERO)
                .coolingPeriodDays(req.getCoolingPeriodDays() != null ? req.getCoolingPeriodDays() : 30)
                .maxLoansPerYear(req.getMaxLoansPerYear() != null ? req.getMaxLoansPerYear() : 2)
                .approvalThresholdKobo(req.getApprovalThresholdKobo() != null ? req.getApprovalThresholdKobo() : 10_000_000L)
                .eligibleAfterMonths(req.getEligibleAfterMonths() != null ? req.getEligibleAfterMonths() : 6)
                .probationEligible(Boolean.TRUE.equals(req.getProbationEligible()))
                .isActive(req.getIsActive() == null || req.getIsActive())
                .build();

        LoanPolicy saved = policyRepository.save(policy);
        log.info("LoanPolicy {} created for tenant {}", saved.getId(), tenantId);
        return saved;
    }

    @Transactional
    public LoanPolicy updatePolicy(UUID policyId, LoanPolicyRequest req) {
        LoanPolicy policy = requirePolicy(policyId);
        if (req.getName() != null) policy.setName(req.getName().trim());
        if (req.getDescription() != null) policy.setDescription(req.getDescription());
        if (req.getMinAmountKobo() != null) policy.setMinAmountKobo(req.getMinAmountKobo());
        if (req.getMaxAmountKobo() != null) policy.setMaxAmountKobo(req.getMaxAmountKobo());
        if (req.getMaxMultiplierOfGross() != null) policy.setMaxMultiplierOfGross(req.getMaxMultiplierOfGross());
        if (req.getMaxDeductionPct() != null) policy.setMaxDeductionPct(req.getMaxDeductionPct());
        if (req.getNetFloorPct() != null) policy.setNetFloorPct(req.getNetFloorPct());
        if (req.getMaxTenorMonths() != null) policy.setMaxTenorMonths(req.getMaxTenorMonths());
        if (req.getInterestRatePct() != null) policy.setInterestRatePct(req.getInterestRatePct());
        if (req.getCoolingPeriodDays() != null) policy.setCoolingPeriodDays(req.getCoolingPeriodDays());
        if (req.getMaxLoansPerYear() != null) policy.setMaxLoansPerYear(req.getMaxLoansPerYear());
        if (req.getApprovalThresholdKobo() != null) policy.setApprovalThresholdKobo(req.getApprovalThresholdKobo());
        if (req.getEligibleAfterMonths() != null) policy.setEligibleAfterMonths(req.getEligibleAfterMonths());
        if (req.getProbationEligible() != null) policy.setProbationEligible(req.getProbationEligible());
        if (req.getIsActive() != null) policy.setIsActive(req.getIsActive());
        return policyRepository.save(policy);
    }

    @Transactional
    public void deletePolicy(UUID policyId) {
        LoanPolicy policy = requirePolicy(policyId);
        policy.setDeletedAt(OffsetDateTime.now());
        policy.setIsActive(false);
        policyRepository.save(policy);
        log.info("LoanPolicy {} soft-deleted", policyId);
    }

    @Transactional(readOnly = true)
    public List<LoanPolicy> getPoliciesForTenant() {
        return policyRepository.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public LoanPolicy getPolicy(UUID policyId) {
        return requirePolicy(policyId);
    }

    // ── Policy resolution ─────────────────────────────────────────────────────

    /**
     * Resolves the effective policy for an employee:
     * pay-grade-linked policy → first active tenant policy → empty.
     */
    @Transactional(readOnly = true)
    public Optional<LoanPolicy> getPolicyForEmployee(Employee employee) {
        if (employee != null && employee.getPayGrade() != null) {
            LoanPolicy gradePolicy = employee.getPayGrade().getLoanPolicy();
            if (gradePolicy != null && Boolean.TRUE.equals(gradePolicy.getIsActive())) {
                return Optional.of(gradePolicy);
            }
        }
        return policyRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validates a loan application against the employee's effective policy and platform ceilings.
     * Throws {@link IllegalArgumentException} with a user-facing message on the first violation.
     * No-op when no policy is configured for the employee's tenant.
     */
    @Transactional(readOnly = true)
    public void validateLoanRequest(Employee employee, long amountKobo, int months) {
        Optional<LoanPolicy> policyOpt = getPolicyForEmployee(employee);
        if (policyOpt.isEmpty()) {
            return; // no policy → unrestricted
        }
        LoanPolicy p = policyOpt.get();

        // 1. Minimum amount
        if (amountKobo < p.getMinAmountKobo()) {
            throw new IllegalArgumentException(
                    "Loan amount is below the policy minimum of ₦" + koboToNaira(p.getMinAmountKobo()) + ".");
        }

        // 2. Maximum amount — lesser of absolute cap and gross-salary multiplier cap
        long maxAllowed = p.getMaxAmountKobo();
        if (employee.getPayGrade() != null) {
            long gross = employee.getPayGrade().getGrossSalary();
            long grossCap = BigDecimal.valueOf(gross).multiply(p.getMaxMultiplierOfGross()).longValue();
            maxAllowed = Math.min(maxAllowed, grossCap);
        }
        if (amountKobo > maxAllowed) {
            throw new IllegalArgumentException(
                    "Loan amount exceeds the policy maximum of ₦" + koboToNaira(maxAllowed) + ".");
        }

        // 3. Tenor
        if (months > p.getMaxTenorMonths()) {
            throw new IllegalArgumentException(
                    "Repayment tenor of " + months + " months exceeds the policy maximum of " + p.getMaxTenorMonths() + " months.");
        }

        // 4. Tenure eligibility
        LocalDate eligibleFrom = employee.getEmploymentStartDate().plusMonths(p.getEligibleAfterMonths());
        if (LocalDate.now().isBefore(eligibleFrom)) {
            throw new IllegalArgumentException(
                    "Employee is not yet eligible for a loan. Eligibility begins on " + eligibleFrom + ".");
        }

        // 5. Probation
        if (!Boolean.TRUE.equals(p.getProbationEligible())) {
            LocalDate probEnd = employee.getProbationEndDate();
            if (probEnd != null && LocalDate.now().isBefore(probEnd)) {
                throw new IllegalArgumentException(
                        "Employee is on probation until " + probEnd + " and is not eligible for loans under this policy.");
            }
        }

        // 6. Cooling period — count from most recent completed or cancelled loan
        if (p.getCoolingPeriodDays() > 0) {
            loanRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId(), currentTenantId()).stream()
                    .filter(l -> l.getStatus() == LoanStatus.COMPLETED || l.getStatus() == LoanStatus.CANCELLED)
                    .findFirst()
                    .ifPresent(last -> {
                        if (last.getUpdatedAt() != null) {
                            LocalDate completedOn = last.getUpdatedAt().toLocalDate();
                            LocalDate coolsOff = completedOn.plusDays(p.getCoolingPeriodDays());
                            if (LocalDate.now().isBefore(coolsOff)) {
                                throw new IllegalArgumentException(
                                        "A cooling period is in effect after your last loan. Next eligible date: " + coolsOff + ".");
                            }
                        }
                    });
        }

        // 7. Annual loan count
        int currentYear = LocalDate.now().getYear();
        long loansThisYear = loanRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId(), currentTenantId()).stream()
                .filter(l -> l.getStatus() != LoanStatus.REJECTED)
                .filter(l -> l.getCreatedAt() != null && l.getCreatedAt().getYear() == currentYear)
                .count();
        if (loansThisYear >= p.getMaxLoansPerYear()) {
            throw new IllegalArgumentException(
                    "Annual loan limit of " + p.getMaxLoansPerYear() + " reached for " + currentYear + ".");
        }

        // 8. Platform ceiling — gross multiplier
        String ceilMultStr = platformSettingsService.get("loan_max_multiplier_ceiling");
        if (ceilMultStr != null && employee.getPayGrade() != null) {
            long gross = employee.getPayGrade().getGrossSalary();
            long platformGrossCap = BigDecimal.valueOf(gross).multiply(new BigDecimal(ceilMultStr)).longValue();
            if (amountKobo > platformGrossCap) {
                throw new IllegalArgumentException(
                        "Loan amount exceeds the platform ceiling of " + ceilMultStr + "× gross salary (₦" + koboToNaira(platformGrossCap) + ").");
            }
        }

        // 9. Platform ceiling — tenor
        String ceilTenorStr = platformSettingsService.get("loan_max_tenor_months_ceiling");
        if (ceilTenorStr != null) {
            int ceilTenor = Integer.parseInt(ceilTenorStr);
            if (months > ceilTenor) {
                throw new IllegalArgumentException(
                        "Repayment tenor exceeds the platform ceiling of " + ceilTenor + " months.");
            }
        }
    }

    // ── Eligibility check ────────────────────────────────────────────────────

    /**
     * Returns a structured eligibility response for the given employee without throwing.
     * Mirrors the checks in {@link #validateLoanRequest} and exposes policy limits so
     * the UI can render the eligibility card and constrain the application form fields.
     */
    @Transactional(readOnly = true)
    public LoanEligibilityResponse checkEligibility(UUID employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalStateException("Employee not found: " + employeeId));
        Optional<LoanPolicy> policyOpt = getPolicyForEmployee(employee);
        if (policyOpt.isEmpty()) {
            return LoanEligibilityResponse.noPolicyDefaults();
        }
        LoanPolicy p = policyOpt.get();

        long maxAmountKobo = p.getMaxAmountKobo();
        if (employee.getPayGrade() != null && p.getMaxMultiplierOfGross() != null) {
            long gross = employee.getPayGrade().getGrossSalary();
            long grossCap = BigDecimal.valueOf(gross).multiply(p.getMaxMultiplierOfGross()).longValue();
            maxAmountKobo = Math.min(maxAmountKobo, grossCap);
        }
        long maxAmountNaira = maxAmountKobo / 100;

        int maxTenor = p.getMaxTenorMonths() != null ? p.getMaxTenorMonths() : 12;
        double interestRate = p.getInterestRatePct() != null ? p.getInterestRatePct().doubleValue() : 0.0;
        int maxLoansPerYear = p.getMaxLoansPerYear() != null ? p.getMaxLoansPerYear() : 2;
        int coolingDays = p.getCoolingPeriodDays() != null ? p.getCoolingPeriodDays() : 0;

        long activeCount = loanRepository
                .findByEmployeeIdAndStatusOrderByCreatedAtAsc(employee.getId(), LoanStatus.ACTIVE, currentTenantId()).size();
        int currentYear = LocalDate.now().getYear();
        long loansThisYear = loanRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId(), currentTenantId()).stream()
                .filter(l -> l.getStatus() != LoanStatus.REJECTED)
                .filter(l -> l.getCreatedAt() != null && l.getCreatedAt().getYear() == currentYear)
                .count();

        // 1. Tenure eligibility
        if (p.getEligibleAfterMonths() != null && employee.getEmploymentStartDate() != null) {
            LocalDate eligibleFrom = employee.getEmploymentStartDate().plusMonths(p.getEligibleAfterMonths());
            if (LocalDate.now().isBefore(eligibleFrom)) {
                return new LoanEligibilityResponse(false,
                        "Not yet eligible. Loan eligibility begins on " + eligibleFrom + ".",
                        maxAmountNaira, maxTenor, interestRate, activeCount, maxLoansPerYear,
                        loansThisYear, coolingDays, null, p.getName());
            }
        }

        // 2. Probation
        if (!Boolean.TRUE.equals(p.getProbationEligible())) {
            LocalDate probEnd = employee.getProbationEndDate();
            if (probEnd != null && LocalDate.now().isBefore(probEnd)) {
                return new LoanEligibilityResponse(false,
                        "You are on probation until " + probEnd + " and are not eligible for loans under this policy.",
                        maxAmountNaira, maxTenor, interestRate, activeCount, maxLoansPerYear,
                        loansThisYear, coolingDays, null, p.getName());
            }
        }

        // 3. Active loan
        if (activeCount > 0) {
            return new LoanEligibilityResponse(false,
                    "You already have an active loan. It must be completed or waived before a new application.",
                    maxAmountNaira, maxTenor, interestRate, activeCount, maxLoansPerYear,
                    loansThisYear, coolingDays, null, p.getName());
        }

        // 4. Cooling period
        LocalDate cooldownEnds = null;
        if (coolingDays > 0) {
            Optional<EmployeeLoan> lastClosed = loanRepository
                    .findByEmployeeIdOrderByCreatedAtDesc(employee.getId(), currentTenantId()).stream()
                    .filter(l -> l.getStatus() == LoanStatus.COMPLETED || l.getStatus() == LoanStatus.CANCELLED)
                    .findFirst();
            if (lastClosed.isPresent() && lastClosed.get().getUpdatedAt() != null) {
                cooldownEnds = lastClosed.get().getUpdatedAt().toLocalDate().plusDays(coolingDays);
                if (LocalDate.now().isBefore(cooldownEnds)) {
                    return new LoanEligibilityResponse(false,
                            "A cooling period is in effect. Next eligible date: " + cooldownEnds + ".",
                            maxAmountNaira, maxTenor, interestRate, activeCount, maxLoansPerYear,
                            loansThisYear, coolingDays, cooldownEnds, p.getName());
                }
            }
        }

        // 5. Annual loan count
        if (loansThisYear >= maxLoansPerYear) {
            return new LoanEligibilityResponse(false,
                    "Annual loan limit of " + maxLoansPerYear + " reached for " + currentYear + ".",
                    maxAmountNaira, maxTenor, interestRate, activeCount, maxLoansPerYear,
                    loansThisYear, coolingDays, null, p.getName());
        }

        return new LoanEligibilityResponse(true, null,
                maxAmountNaira, maxTenor, interestRate, activeCount, maxLoansPerYear,
                loansThisYear, coolingDays, null, p.getName());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LoanPolicy requirePolicy(UUID id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Loan policy not found: " + id));
    }

    private static String koboToNaira(long kobo) {
        return String.format("%,.2f", kobo / 100.0);
    }

    private UUID currentTenantId() {
        return UUID.fromString(com.admtechhub.maestrohr.auth.TenantContext.getCurrentTenant());
    }
}
