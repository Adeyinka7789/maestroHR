package com.admtechhub.maestrohr.exit;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.leave.LeaveBalance;
import com.admtechhub.maestrohr.leave.LeaveBalanceRepository;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExitService {

    private final ExitRequestRepository exitRequestRepository;
    private final ClearanceItemRepository clearanceItemRepository;
    private final EmployeeClearanceRepository employeeClearanceRepository;
    private final FinalSettlementRepository finalSettlementRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final EmployeeService employeeService;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LoanService loanService;
    private final AuditTrailService auditTrailService;

    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        return odt != null ? odt.toLocalDateTime() : null;
    }

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    // ---------- DTO Conversions ----------

    private ExitRequestDTO toDto(ExitRequest req, Integer clearedCount, Integer totalCount,
                                 String settlementStatus,
                                 List<EmployeeClearanceDTO> clearances, FinalSettlementDTO settlement) {
        int progress = (totalCount == null || totalCount == 0) ? 100
                : (int) (clearedCount * 100 / totalCount);
        return ExitRequestDTO.builder()
                .id(req.getId())
                .employeeId(req.getEmployee().getId())
                .employeeName(req.getEmployee().getFullName())
                .employeeNumber(req.getEmployee().getEmployeeNumber())
                .exitType(req.getExitType())
                .lastWorkingDay(req.getLastWorkingDay())
                .reason(req.getReason())
                .status(req.getStatus())
                .createdBy(req.getCreatedBy())
                .createdAt(toLocalDateTime(req.getCreatedAt()))
                .clearanceProgress(progress)
                .settlementStatus(settlementStatus != null ? settlementStatus : "PENDING")
                .clearanceItems(clearances != null ? clearances : List.of())
                .settlement(settlement)
                .build();
    }

    private EmployeeClearanceDTO toDto(EmployeeClearance ec) {
        return EmployeeClearanceDTO.builder()
                .id(ec.getId())
                .exitRequestId(ec.getExitRequest().getId())
                .clearanceItemId(ec.getClearanceItem().getId())
                .clearanceItemName(ec.getClearanceItem().getName())
                .clearanceItemDepartment(ec.getClearanceItem().getDepartment())
                .status(ec.getStatus())
                .clearedBy(ec.getClearedBy())
                .clearedAt(toLocalDateTime(ec.getClearedAt()))
                .notes(ec.getNotes())
                .build();
    }

    private FinalSettlementDTO toDto(FinalSettlement fs) {
        return FinalSettlementDTO.builder()
                .id(fs.getId())
                .exitRequestId(fs.getExitRequest().getId())
                .unpaidSalary(fs.getUnpaidSalary())
                .accruedLeave(fs.getAccruedLeave())
                .severancePay(fs.getSeverancePay())
                .loanDeduction(fs.getLoanDeduction())
                .otherDeductions(fs.getOtherDeductions())
                .totalPayable(fs.getTotalPayable())
                .paymentStatus(fs.getPaymentStatus())
                .paymentDate(fs.getPaymentDate())
                .notes(fs.getNotes())
                .build();
    }

    // ---------- Business Methods ----------

    @Transactional
    public ExitRequestDTO createExitRequest(UUID employeeId, String exitType,
                                            LocalDate lastWorkingDay, String reason) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        ExitRequest request = ExitRequest.builder()
                .tenant(tenant)
                .employee(employee)
                .exitType(exitType)
                .lastWorkingDay(lastWorkingDay)
                .reason(reason)
                .status("IN_CLEARANCE")
                .createdBy(currentUserEmail())
                .build();
        ExitRequest saved = exitRequestRepository.save(request);

        // Seed one EmployeeClearance row per tenant clearance item
        List<ClearanceItem> items = clearanceItemRepository.findByTenantId(tenantId);
        List<EmployeeClearance> rows = items.stream()
                .map(item -> EmployeeClearance.builder()
                        .tenant(tenant)
                        .exitRequest(saved)
                        .clearanceItem(item)
                        .status("PENDING")
                        .build())
                .collect(Collectors.toList());
        employeeClearanceRepository.saveAll(rows);

        // If tenant has no clearance items, clearance is immediately complete
        if (items.isEmpty()) {
            saved.setStatus("CLEARANCE_COMPLETE");
            exitRequestRepository.save(saved);
        }

        auditTrailService.record(tenantId, currentUserEmail(), "EXIT_REQUEST_CREATED",
                "EXIT_REQUEST", saved.getId().toString(), "/exit/requests", "POST",
                null, 201, "Employee: " + employee.getFullName() + ", Type: " + exitType);

        return toDto(saved, 0, items.size(), "PENDING", List.of(), null);
    }

    @Transactional(readOnly = true)
    public List<ExitRequestDTO> getAllExitRequests() {
        UUID tenantId = getCurrentTenantId();
        List<ExitRequest> requests = exitRequestRepository.findByTenantId(tenantId);

        return requests.stream().map(req -> {
            long cleared = employeeClearanceRepository.countClearedByExitRequestId(req.getId());
            long total = employeeClearanceRepository.countByExitRequestId(req.getId());
            String settlementStatus = finalSettlementRepository.findByExitRequestId(req.getId())
                    .map(FinalSettlement::getPaymentStatus).orElse("PENDING");
            return toDto(req, (int) cleared, (int) total, settlementStatus, List.of(), null);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ExitRequestDTO getExitRequestById(UUID id) {
        ExitRequest req = exitRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));
        long cleared = employeeClearanceRepository.countClearedByExitRequestId(req.getId());
        long total = employeeClearanceRepository.countByExitRequestId(req.getId());
        String settlementStatus = finalSettlementRepository.findByExitRequestId(req.getId())
                .map(FinalSettlement::getPaymentStatus).orElse("PENDING");
        List<EmployeeClearanceDTO> clearanceDtos = employeeClearanceRepository
                .findByExitRequestId(id).stream().map(this::toDto).collect(Collectors.toList());
        FinalSettlementDTO settlementDto = finalSettlementRepository.findByExitRequestId(req.getId())
                .map(this::toDto).orElse(null);
        return toDto(req, (int) cleared, (int) total, settlementStatus, clearanceDtos, settlementDto);
    }

    @Transactional
    public void updateClearanceItem(UUID exitRequestId, UUID itemId, String status, String clearedBy) {
        EmployeeClearance ec = employeeClearanceRepository.findByExitRequestId(exitRequestId).stream()
                .filter(e -> e.getClearanceItem().getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Clearance item not found for this exit request"));
        ec.setStatus(status);
        ec.setClearedBy(clearedBy);
        ec.setClearedAt(OffsetDateTime.now());
        employeeClearanceRepository.save(ec);

        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId).orElseThrow();
        long total = employeeClearanceRepository.countByExitRequestId(exitRequestId);
        long cleared = employeeClearanceRepository.countClearedByExitRequestId(exitRequestId);
        if (cleared == total) {
            exitRequest.setStatus("CLEARANCE_COMPLETE");
            exitRequestRepository.save(exitRequest);
            log.info("All {} clearance items cleared for exit request {} — status → CLEARANCE_COMPLETE",
                    total, exitRequestId);
        }

        UUID tenantId = getCurrentTenantId();
        auditTrailService.record(tenantId, currentUserEmail(), "CLEARANCE_ITEM_COMPLETED",
                "EXIT_REQUEST", exitRequestId.toString(), "/exit/clearance", "PUT",
                null, 200,
                "Item: " + ec.getClearanceItem().getName() + ", Cleared by: " + clearedBy);
    }

    /** Auto-calculates settlement amounts from live HR data. Returns a pre-populated DTO
     *  for the settlement form; does NOT persist anything. */
    @Transactional(readOnly = true)
    public FinalSettlementDTO calculateAutoSettlement(UUID exitRequestId) {
        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));
        Employee employee = exitRequest.getEmployee();

        if (employee.getPayGrade() == null) {
            throw new IllegalStateException("Employee has no pay grade assigned — cannot auto-calculate settlement");
        }

        long grossKobo = employee.getPayGrade().getGrossSalary();
        BigDecimal grossMonthly = BigDecimal.valueOf(grossKobo, 2); // kobo → naira

        // Prorate unpaid salary for the exit month
        LocalDate lastDay = exitRequest.getLastWorkingDay() != null
                ? exitRequest.getLastWorkingDay() : LocalDate.now();
        LocalDate periodStart = lastDay.withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);
        int workingDaysInMonth = countWorkingDays(periodStart, periodEnd);
        int daysWorked = countWorkingDays(periodStart, lastDay);
        BigDecimal unpaidSalary = workingDaysInMonth == 0 ? BigDecimal.ZERO
                : grossMonthly.multiply(BigDecimal.valueOf(daysWorked))
                        .divide(BigDecimal.valueOf(workingDaysInMonth), 2, RoundingMode.HALF_UP);

        // Accrued leave payout: remaining days × (monthly gross / 26 average working days)
        int year = lastDay.getYear();
        int remainingLeaveDays = leaveBalanceRepository.findByEmployeeIdAndYear(employee.getId(), year)
                .stream().mapToInt(LeaveBalance::getDaysRemaining).sum();
        BigDecimal dailyRate = grossMonthly.divide(BigDecimal.valueOf(26), 2, RoundingMode.HALF_UP);
        BigDecimal accruedLeave = dailyRate.multiply(BigDecimal.valueOf(remainingLeaveDays));

        // Outstanding loan balance — deducted from final pay
        BigDecimal loanDeduction = loanService.getOutstandingLoanBalance(employee.getId());

        BigDecimal total = unpaidSalary.add(accruedLeave).subtract(loanDeduction);

        return FinalSettlementDTO.builder()
                .exitRequestId(exitRequestId)
                .unpaidSalary(unpaidSalary)
                .accruedLeave(accruedLeave)
                .loanDeduction(loanDeduction)
                .severancePay(BigDecimal.ZERO)
                .otherDeductions(BigDecimal.ZERO)
                .totalPayable(total.max(BigDecimal.ZERO))
                .paymentStatus("PENDING")
                .build();
    }

    /** Persists the settlement (using caller-supplied values so HR can adjust auto-calculated
     *  amounts), terminates the employee, and advances status to SETTLED. */
    @Transactional
    public FinalSettlementDTO calculateSettlement(UUID exitRequestId, BigDecimal unpaidSalary,
                                                   BigDecimal accruedLeave, BigDecimal severancePay,
                                                   BigDecimal loanDeduction, BigDecimal otherDeductions) {
        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));

        if (!"CLEARANCE_COMPLETE".equals(exitRequest.getStatus())) {
            throw new IllegalStateException(
                    "Cannot calculate settlement until all clearance items are completed");
        }

        BigDecimal safeUnpaid = coalesce(unpaidSalary);
        BigDecimal safeLeave = coalesce(accruedLeave);
        BigDecimal safeSeverance = coalesce(severancePay);
        BigDecimal safeLoan = coalesce(loanDeduction);
        BigDecimal safeOther = coalesce(otherDeductions);
        BigDecimal total = safeUnpaid.add(safeLeave).add(safeSeverance)
                .subtract(safeLoan).subtract(safeOther);

        // Upsert — allow recalculation before marking paid
        FinalSettlement settlement = finalSettlementRepository.findByExitRequestId(exitRequestId)
                .orElse(FinalSettlement.builder()
                        .tenant(exitRequest.getTenant())
                        .exitRequest(exitRequest)
                        .paymentStatus("PENDING")
                        .build());
        settlement.setUnpaidSalary(safeUnpaid);
        settlement.setAccruedLeave(safeLeave);
        settlement.setSeverancePay(safeSeverance);
        settlement.setLoanDeduction(safeLoan);
        settlement.setOtherDeductions(safeOther);
        settlement.setTotalPayable(total);
        settlement.setPaymentStatus("PENDING");
        finalSettlementRepository.save(settlement);

        // Terminate employee
        Employee employee = exitRequest.getEmployee();
        employeeService.terminateEmployee(employee.getId(), exitRequest.getLastWorkingDay());

        exitRequest.setStatus("SETTLED");
        exitRequestRepository.save(exitRequest);

        UUID tenantId = getCurrentTenantId();
        auditTrailService.record(tenantId, currentUserEmail(), "SETTLEMENT_CALCULATED",
                "EXIT_REQUEST", exitRequestId.toString(), "/exit/settlement", "POST",
                null, 200,
                "Total payable: " + total + " for " + employee.getFullName());

        return toDto(settlement);
    }

    @Transactional
    public void markPaid(UUID exitRequestId) {
        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));

        if (!"SETTLED".equals(exitRequest.getStatus())) {
            throw new IllegalStateException(
                    "Cannot mark as paid before settlement is calculated (status must be SETTLED)");
        }

        FinalSettlement settlement = finalSettlementRepository.findByExitRequestId(exitRequestId)
                .orElseThrow(() -> new IllegalStateException("No settlement found for this exit request"));
        settlement.setPaymentStatus("PAID");
        settlement.setPaymentDate(LocalDate.now());
        finalSettlementRepository.save(settlement);

        exitRequest.setStatus("COMPLETED");
        exitRequestRepository.save(exitRequest);

        UUID tenantId = getCurrentTenantId();
        auditTrailService.record(tenantId, currentUserEmail(), "EXIT_COMPLETED",
                "EXIT_REQUEST", exitRequestId.toString(), "/exit/payment", "POST",
                null, 200,
                "Exit process completed for " + exitRequest.getEmployee().getFullName());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        UUID tenantId = getCurrentTenantId();
        Map<String, Object> stats = new HashMap<>();
        stats.put("inClearance", exitRequestRepository.countInClearanceByTenantId(tenantId));
        stats.put("clearanceComplete", exitRequestRepository.countClearanceCompleteByTenantId(tenantId));
        stats.put("settled", exitRequestRepository.countSettledByTenantId(tenantId));
        stats.put("completed", exitRequestRepository.countCompletedByTenantId(tenantId));
        BigDecimal totalPayable = exitRequestRepository.getTotalPendingPayable(tenantId);
        stats.put("totalPayable", totalPayable != null ? totalPayable.doubleValue() : 0);
        return stats;
    }

    // ---------- Helpers ----------

    private int countWorkingDays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() != 7) { // exclude Sunday
                count++;
            }
        }
        return count;
    }

    private BigDecimal coalesce(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
