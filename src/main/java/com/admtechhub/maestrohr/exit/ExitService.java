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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }

    // ---------- Exit Requests ----------
    @Transactional
    public ExitRequest createExitRequest(UUID employeeId, String exitType, LocalDate lastWorkingDay, String reason) {
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
                .createdBy(TenantContext.getCurrentTenant()) // or get current user email
                .build();

        return exitRequestRepository.save(request);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllExitRequests() {
        UUID tenantId = getCurrentTenantId();
        List<ExitRequest> requests = exitRequestRepository.findByTenantId(tenantId);
        return requests.stream().map(req -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", req.getId());
            dto.put("employeeName", req.getEmployee().getFullName());
            dto.put("exitType", req.getExitType());
            dto.put("lastWorkingDay", req.getLastWorkingDay());
            dto.put("status", req.getStatus());

            // Clearance progress
            long cleared = employeeClearanceRepository.countClearedByExitRequestId(req.getId());
            long total = clearanceItemRepository.findByTenantId(tenantId).size();
            int progress = total == 0 ? 0 : (int) (cleared * 100 / total);
            dto.put("clearanceProgress", progress);

            // Settlement status
            finalSettlementRepository.findByExitRequestId(req.getId()).ifPresent(settlement -> {
                dto.put("settlementStatus", settlement.getPaymentStatus());
            });
            if (!dto.containsKey("settlementStatus")) dto.put("settlementStatus", "PENDING");

            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getExitRequestById(UUID id) {
        ExitRequest req = exitRequestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exit request not found"));
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", req.getId());
        dto.put("employeeName", req.getEmployee().getFullName());
        dto.put("exitType", req.getExitType());
        dto.put("lastWorkingDay", req.getLastWorkingDay());
        dto.put("reason", req.getReason());
        dto.put("status", req.getStatus());

        // Clearance items
        List<ClearanceItem> items = clearanceItemRepository.findByTenantId(getCurrentTenantId());
        List<EmployeeClearance> clearances = employeeClearanceRepository.findByExitRequestId(id);
        Map<UUID, String> clearanceStatus = clearances.stream()
                .collect(Collectors.toMap(ec -> ec.getClearanceItem().getId(), EmployeeClearance::getStatus));
        List<Map<String, Object>> clearanceList = items.stream().map(item -> {
            Map<String, Object> itemDto = new HashMap<>();
            itemDto.put("id", item.getId());
            itemDto.put("name", item.getName());
            itemDto.put("status", clearanceStatus.getOrDefault(item.getId(), "PENDING"));
            return itemDto;
        }).collect(Collectors.toList());
        dto.put("clearance", clearanceList);

        // Settlement
        finalSettlementRepository.findByExitRequestId(id).ifPresent(settlement -> {
            Map<String, Object> settlementDto = new HashMap<>();
            settlementDto.put("unpaidSalary", settlement.getUnpaidSalary());
            settlementDto.put("accruedLeave", settlement.getAccruedLeave());
            settlementDto.put("severancePay", settlement.getSeverancePay());
            settlementDto.put("otherDeductions", settlement.getOtherDeductions());
            settlementDto.put("totalPayable", settlement.getTotalPayable());
            settlementDto.put("paymentStatus", settlement.getPaymentStatus());
            dto.put("settlement", settlementDto);
        });

        return dto;
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

        // If all cleared, update exit request status to IN_CLEARANCE (or COMPLETED if also settlement done)
        ExitRequest exitRequest = exitRequestRepository.findById(exitRequestId).orElseThrow();
        long total = clearanceItemRepository.findByTenantId(getCurrentTenantId()).size();
        long cleared = employeeClearanceRepository.countClearedByExitRequestId(exitRequestId);
        if (cleared == total) {
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