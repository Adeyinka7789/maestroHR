package com.admtechhub.maestrohr.exit;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    // Helper to convert OffsetDateTime to LocalDateTime
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

    // ---------- DTO Conversions ----------
    private ExitRequestDTO toDto(ExitRequest req, Integer progress, String settlementStatus,
                                 List<EmployeeClearanceDTO> clearances, FinalSettlementDTO settlement) {
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
                .clearanceProgress(progress != null ? progress : 0)
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
                .otherDeductions(fs.getOtherDeductions())
                .totalPayable(fs.getTotalPayable())
                .paymentStatus(fs.getPaymentStatus())
                .paymentDate(fs.getPaymentDate())
                .notes(fs.getNotes())
                .build();
    }

    private List<ClearanceItemDTO> getClearanceItemDtos(UUID tenantId) {
        return clearanceItemRepository.findByTenantId(tenantId).stream()
                .map(item -> ClearanceItemDTO.builder()
                        .id(item.getId())
                        .name(item.getName())
                        .department(item.getDepartment())
                        .sortOrder(item.getSortOrder())
                        .isRequired(item.getIsRequired())
                        .build())
                .collect(Collectors.toList());
    }

    // ---------- Business Methods (return DTOs) ----------
    @Transactional
    public ExitRequestDTO createExitRequest(UUID employeeId, String exitType, LocalDate lastWorkingDay, String reason) {
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
                .status("PENDING")
                .createdBy(TenantContext.getCurrentTenant()) // or better: current user email
                .build();

        ExitRequest saved = exitRequestRepository.save(request);
        return toDto(saved, 0, "PENDING", List.of(), null);
    }

    @Transactional(readOnly = true)
    public List<ExitRequestDTO> getAllExitRequests() {
        UUID tenantId = getCurrentTenantId();
        List<ExitRequest> requests = exitRequestRepository.findByTenantId(tenantId);
        List<ClearanceItem> allItems = clearanceItemRepository.findByTenantId(tenantId);
        int totalItems = allItems.size();

        return requests.stream().map(req -> {
            long cleared = employeeClearanceRepository.countClearedByExitRequestId(req.getId());
            int progress = totalItems == 0 ? 0 : (int) (cleared * 100 / totalItems);
            String settlementStatus = finalSettlementRepository.findByExitRequestId(req.getId())
                    .map(FinalSettlement::getPaymentStatus).orElse("PENDING");
            return toDto(req, progress, settlementStatus, List.of(), null);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ExitRequestDTO getExitRequestById(UUID id) {
        ExitRequest req = exitRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));
        UUID tenantId = getCurrentTenantId();
        List<ClearanceItem> allItems = clearanceItemRepository.findByTenantId(tenantId);
        int totalItems = allItems.size();
        long cleared = employeeClearanceRepository.countClearedByExitRequestId(req.getId());
        int progress = totalItems == 0 ? 0 : (int) (cleared * 100 / totalItems);
        String settlementStatus = finalSettlementRepository.findByExitRequestId(req.getId())
                .map(FinalSettlement::getPaymentStatus).orElse("PENDING");

        List<EmployeeClearanceDTO> clearanceDtos = employeeClearanceRepository.findByExitRequestId(id).stream()
                .map(this::toDto).collect(Collectors.toList());
        FinalSettlementDTO settlementDto = finalSettlementRepository.findByExitRequestId(req.getId())
                .map(this::toDto).orElse(null);

        return toDto(req, progress, settlementStatus, clearanceDtos, settlementDto);
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

        // Update exit request status if all cleared
        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId).orElseThrow();
        long total = clearanceItemRepository.findByTenantId(getCurrentTenantId()).size();
        long cleared = employeeClearanceRepository.countClearedByExitRequestId(exitRequestId);
        if (cleared == total && total > 0) {
            exitRequest.setStatus("IN_CLEARANCE");
            exitRequestRepository.save(exitRequest);
        }
    }

    @Transactional
    public void calculateSettlement(UUID exitRequestId, BigDecimal unpaidSalary, BigDecimal accruedLeave,
                                    BigDecimal severancePay, BigDecimal otherDeductions) {
        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));
        BigDecimal total = unpaidSalary.add(accruedLeave).add(severancePay).subtract(otherDeductions);
        FinalSettlement settlement = FinalSettlement.builder()
                .tenant(exitRequest.getTenant())
                .exitRequest(exitRequest)
                .unpaidSalary(unpaidSalary)
                .accruedLeave(accruedLeave)
                .severancePay(severancePay)
                .otherDeductions(otherDeductions)
                .totalPayable(total)
                .paymentStatus("PENDING")
                .build();
        finalSettlementRepository.save(settlement);
        exitRequest.setStatus("COMPLETED");
        exitRequestRepository.save(exitRequest);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        UUID tenantId = getCurrentTenantId();
        Map<String, Object> stats = new HashMap<>();
        stats.put("pending", exitRequestRepository.countPendingByTenantId(tenantId));
        stats.put("inClearance", exitRequestRepository.countInClearanceByTenantId(tenantId));
        stats.put("completed", exitRequestRepository.countCompletedByTenantId(tenantId));
        BigDecimal totalPayable = exitRequestRepository.getTotalPendingPayable(tenantId);
        stats.put("totalPayable", totalPayable != null ? totalPayable.doubleValue() : 0);
        return stats;
    }
}