package com.admtechhub.maestrohr.adjustment;

import com.admtechhub.maestrohr.adjustment.AdjustmentDTOs.*;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Statutory Reimbursements & Deductions manager. Owns the per-tenant {@link AdjustmentType}
 * catalogue and the {@link PayrollAdjustment} entries logged against employees, and exposes the
 * batch compute / apply / reverse hooks the payroll run uses — the same lifecycle employee loans
 * follow (compute at run-build, consume at approval, reverse on run reversal).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollAdjustmentService {

    private final AdjustmentTypeRepository typeRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final EmployeeRepository employeeRepository;

    /** Default catalogue seeded on first use per tenant. name, code, direction, tax treatment. */
    private record DefaultType(String name, String code, AdjustmentDirection dir, AdjustmentTaxTreatment tax) {}

    private static final List<DefaultType> DEFAULT_TYPES = List.of(
            new DefaultType("Performance Bonus", "PERF_BONUS", AdjustmentDirection.EARNING, AdjustmentTaxTreatment.TAXABLE),
            new DefaultType("Overtime", "OVERTIME", AdjustmentDirection.EARNING, AdjustmentTaxTreatment.TAXABLE),
            new DefaultType("Transport Reimbursement", "TRANSPORT_REIMB", AdjustmentDirection.EARNING, AdjustmentTaxTreatment.NON_TAXABLE),
            new DefaultType("Expense Reimbursement", "EXPENSE_REIMB", AdjustmentDirection.EARNING, AdjustmentTaxTreatment.NON_TAXABLE),
            new DefaultType("Late Coming Fine", "LATE_FINE", AdjustmentDirection.DEDUCTION, AdjustmentTaxTreatment.POST_TAX),
            new DefaultType("Damaged / Lost Item", "DAMAGE", AdjustmentDirection.DEDUCTION, AdjustmentTaxTreatment.POST_TAX),
            new DefaultType("Salary Advance Recovery", "ADVANCE", AdjustmentDirection.DEDUCTION, AdjustmentTaxTreatment.POST_TAX),
            new DefaultType("Voluntary Pension (AVC)", "AVC", AdjustmentDirection.DEDUCTION, AdjustmentTaxTreatment.PRE_TAX)
    );

    // ── Types ────────────────────────────────────────────────────────────────────

    /**
     * Seed the default catalogue the first time a tenant has none. Runs in the caller's
     * authenticated, tenant-bound transaction (so RLS is satisfied), unlike the registration-time
     * seeding which must go through the privileged path.
     */
    @Transactional
    public void ensureDefaultsSeeded() {
        if (typeRepository.count() > 0) {
            return;
        }
        UUID tenantId = currentTenantId();
        List<AdjustmentType> defaults = DEFAULT_TYPES.stream()
                .map(d -> AdjustmentType.builder()
                        .tenantId(tenantId).name(d.name()).code(d.code())
                        .direction(d.dir()).taxTreatment(d.tax())
                        .active(true).system(true).build())
                .collect(Collectors.toList());
        typeRepository.saveAll(defaults);
        log.info("Seeded {} default adjustment types for tenant {}", defaults.size(), tenantId);
    }

    @Transactional
    public List<TypeView> listTypes(boolean includeInactive) {
        ensureDefaultsSeeded();
        List<AdjustmentType> types = includeInactive
                ? typeRepository.findAllByOrderByDirectionAscNameAsc()
                : typeRepository.findByActiveTrueOrderByDirectionAscNameAsc();
        return types.stream().map(TypeView::from).toList();
    }

    @Transactional
    public TypeView createType(CreateTypeRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("Type name is required.");
        }
        AdjustmentType type = AdjustmentType.builder()
                .tenantId(currentTenantId())
                .name(req.name().trim())
                .code(generateUniqueCode(req.name()))
                .direction(req.direction())
                .taxTreatment(req.taxTreatment())
                .active(true)
                .system(false)
                .build();
        type.validate();
        return TypeView.from(typeRepository.save(type));
    }

    @Transactional
    public TypeView setTypeActive(UUID typeId, boolean active) {
        AdjustmentType type = typeRepository.findById(typeId)
                .orElseThrow(() -> new IllegalArgumentException("Adjustment type not found"));
        type.setActive(active);
        return TypeView.from(typeRepository.save(type));
    }

    // ── Adjustments ──────────────────────────────────────────────────────────────

    @Transactional
    public AdjustmentView create(CreateAdjustmentRequest req, String createdBy) {
        if (req.amountKobo() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (req.periodMonth() < 1 || req.periodMonth() > 12) {
            throw new IllegalArgumentException("Invalid month.");
        }
        Employee employee = employeeRepository.findById(req.employeeId())
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        AdjustmentType type = typeRepository.findById(req.typeId())
                .orElseThrow(() -> new IllegalArgumentException("Adjustment type not found"));
        if (!type.isActive()) {
            throw new IllegalArgumentException("That adjustment type is inactive.");
        }

        PayrollAdjustment adj = PayrollAdjustment.builder()
                .tenantId(currentTenantId())
                .employeeId(employee.getId())
                .adjustmentTypeId(type.getId())
                .amountKobo(req.amountKobo())
                .periodMonth(req.periodMonth())
                .periodYear(req.periodYear())
                .note(req.note())
                .status(AdjustmentStatus.PENDING)
                .createdBy(createdBy)
                .build();
        PayrollAdjustment saved = adjustmentRepository.save(adj);
        return toView(saved, Map.of(employee.getId(), employee), Map.of(type.getId(), type));
    }

    /** Cancel a PENDING adjustment. Applied ones can only be undone by reversing their run. */
    @Transactional
    public void cancel(UUID adjustmentId) {
        PayrollAdjustment adj = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Adjustment not found"));
        if (adj.getStatus() != AdjustmentStatus.PENDING) {
            throw new IllegalStateException("Only pending adjustments can be cancelled.");
        }
        adj.setStatus(AdjustmentStatus.CANCELLED);
        adjustmentRepository.save(adj);
    }

    @Transactional(readOnly = true)
    public List<AdjustmentView> listForPeriod(int month, int year) {
        List<PayrollAdjustment> rows = adjustmentRepository
                .findByPeriodYearAndPeriodMonthOrderByCreatedAtDesc(year, month);
        return toViews(rows);
    }

    // ── Payroll-run lifecycle (mirrors LoanService) ──────────────────────────────

    /**
     * Aggregate each employee's PENDING adjustments for the period into engine buckets. Read-only;
     * items are not consumed until {@link #applyForRun}. Employees with no adjustments are absent
     * from the map (the caller defaults to {@link AdjustmentBuckets#zero()}).
     */
    @Transactional(readOnly = true)
    public Map<UUID, AdjustmentBuckets> computeBucketsForPeriod(Collection<UUID> employeeIds, int month, int year) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = new HashSet<>(employeeIds);
        List<PayrollAdjustment> pending = adjustmentRepository
                .findByPeriodYearAndPeriodMonthAndStatus(year, month, AdjustmentStatus.PENDING);
        Map<UUID, AdjustmentType> typeById = typeIndex(pending);

        Map<UUID, AdjustmentBuckets> byEmployee = new HashMap<>();
        for (PayrollAdjustment a : pending) {
            if (!ids.contains(a.getEmployeeId())) {
                continue;
            }
            AdjustmentType type = typeById.get(a.getAdjustmentTypeId());
            if (type == null) {
                continue;
            }
            byEmployee.merge(a.getEmployeeId(),
                    AdjustmentBuckets.zero().plus(type.getDirection(), type.getTaxTreatment(), a.getAmountKobo()),
                    (existing, add) -> existing.plus(type.getDirection(), type.getTaxTreatment(), a.getAmountKobo()));
        }
        return byEmployee;
    }

    /**
     * Consume the run's period adjustments: flip the PENDING items for employees in the run to
     * APPLIED and stamp the run id. Called when a run is approved (compute is re-runnable, so
     * consumption happens once, at approval — exactly like loan repayments).
     */
    @Transactional
    public void applyForRun(PayrollRun run, List<PayrollEntry> entries) {
        Set<UUID> runEmployeeIds = entries.stream()
                .map(e -> e.getEmployee().getId()).collect(Collectors.toSet());
        List<PayrollAdjustment> pending = adjustmentRepository.findByPeriodYearAndPeriodMonthAndStatus(
                run.getPayrollYear(), run.getPayrollMonth(), AdjustmentStatus.PENDING);
        OffsetDateTime now = OffsetDateTime.now();
        int applied = 0;
        for (PayrollAdjustment a : pending) {
            if (!runEmployeeIds.contains(a.getEmployeeId())) {
                continue;
            }
            a.setStatus(AdjustmentStatus.APPLIED);
            a.setPayrollRunId(run.getId());
            a.setAppliedAt(now);
            applied++;
        }
        adjustmentRepository.saveAll(pending);
        log.info("Applied {} adjustments to payroll run {}", applied, run.getId());
    }

    /** Reverse a run's adjustments: APPLIED items for the run go back to PENDING for a future run. */
    @Transactional
    public void reverseForRun(UUID payrollRunId) {
        List<PayrollAdjustment> applied = adjustmentRepository
                .findByPayrollRunIdAndStatus(payrollRunId, AdjustmentStatus.APPLIED);
        for (PayrollAdjustment a : applied) {
            a.setStatus(AdjustmentStatus.PENDING);
            a.setPayrollRunId(null);
            a.setAppliedAt(null);
        }
        adjustmentRepository.saveAll(applied);
        if (!applied.isEmpty()) {
            log.info("Reversed {} adjustments from payroll run {}", applied.size(), payrollRunId);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<AdjustmentView> toViews(List<PayrollAdjustment> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> employees = employeeRepository.findAllById(
                        rows.stream().map(PayrollAdjustment::getEmployeeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Employee::getId, e -> e));
        Map<UUID, AdjustmentType> types = typeIndex(rows);
        return rows.stream().map(a -> toView(a, employees, types)).toList();
    }

    private AdjustmentView toView(PayrollAdjustment a, Map<UUID, Employee> employees,
                                  Map<UUID, AdjustmentType> types) {
        Employee emp = employees.get(a.getEmployeeId());
        AdjustmentType type = types.get(a.getAdjustmentTypeId());
        return new AdjustmentView(
                a.getId(), a.getEmployeeId(), emp != null ? emp.getFullName() : "—",
                a.getAdjustmentTypeId(), type != null ? type.getName() : "—",
                type != null ? type.getDirection() : null,
                type != null ? type.getTaxTreatment() : null,
                a.getAmountKobo(), a.getPeriodMonth(), a.getPeriodYear(), a.getNote(),
                a.getStatus(), a.getCreatedBy(), a.getCreatedAt());
    }

    private Map<UUID, AdjustmentType> typeIndex(List<PayrollAdjustment> rows) {
        Set<UUID> typeIds = rows.stream().map(PayrollAdjustment::getAdjustmentTypeId).collect(Collectors.toSet());
        return typeRepository.findAllById(typeIds).stream()
                .collect(Collectors.toMap(AdjustmentType::getId, t -> t));
    }

    private String generateUniqueCode(String name) {
        String base = name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_+)|(_+$)", "");
        if (base.isBlank()) {
            base = "CUSTOM";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String code = base;
        int n = 2;
        while (typeRepository.findByCode(code).isPresent()) {
            code = base + "_" + n++;
        }
        return code;
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
