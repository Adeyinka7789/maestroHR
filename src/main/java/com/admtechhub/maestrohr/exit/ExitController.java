package com.admtechhub.maestrohr.exit;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/exit")
@RequiredArgsConstructor
@Slf4j
public class ExitController {

    private final ExitService exitService;

    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<ExitRequestDTO>>> getExitRequests() {
        return ResponseEntity.ok(ApiResponse.success("Exit requests retrieved",
                exitService.getAllExitRequests()));
    }

    @GetMapping("/requests/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ExitRequestDTO>> getExitRequest(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Exit request details",
                exitService.getExitRequestById(id)));
    }

    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ExitRequestDTO>> createExitRequest(
            @RequestBody Map<String, Object> payload) {
        UUID employeeId = UUID.fromString((String) payload.get("employeeId"));
        String exitType = (String) payload.get("exitType");
        LocalDate lastWorkingDay = LocalDate.parse((String) payload.get("lastWorkingDay"));
        String reason = (String) payload.get("reason");
        ExitRequestDTO dto = exitService.createExitRequest(employeeId, exitType, lastWorkingDay, reason);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Exit request created", dto));
    }

    @PutMapping("/clearance/{exitRequestId}/{itemId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updateClearance(
            @PathVariable UUID exitRequestId,
            @PathVariable UUID itemId,
            @RequestParam String status,
            Authentication authentication) {
        String clearedBy = authentication != null ? authentication.getName() : "system";
        exitService.updateClearanceItem(exitRequestId, itemId, status, clearedBy);
        return ResponseEntity.ok(ApiResponse.success("Clearance updated", null));
    }

    @GetMapping("/settlement/{exitRequestId}/auto")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<FinalSettlementDTO>> autoSettlement(
            @PathVariable UUID exitRequestId) {
        FinalSettlementDTO dto = exitService.calculateAutoSettlement(exitRequestId);
        return ResponseEntity.ok(ApiResponse.success("Auto-calculated settlement", dto));
    }

    @PostMapping("/settlement/{exitRequestId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<FinalSettlementDTO>> calculateSettlement(
            @PathVariable UUID exitRequestId,
            @RequestBody Map<String, Object> payload) {
        BigDecimal unpaidSalary = parseBd(payload.get("unpaidSalary"));
        BigDecimal accruedLeave = parseBd(payload.get("accruedLeave"));
        BigDecimal severancePay = parseBd(payload.get("severancePay"));
        BigDecimal loanDeduction = parseBd(payload.get("loanDeduction"));
        BigDecimal otherDeductions = parseBd(payload.get("otherDeductions"));
        FinalSettlementDTO dto = exitService.calculateSettlement(
                exitRequestId, unpaidSalary, accruedLeave, severancePay, loanDeduction, otherDeductions);
        return ResponseEntity.ok(ApiResponse.success("Settlement calculated", dto));
    }

    @PostMapping("/settlement/{exitRequestId}/mark-paid")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> markPaid(@PathVariable UUID exitRequestId) {
        exitService.markPaid(exitRequestId);
        return ResponseEntity.ok(ApiResponse.success("Settlement marked as paid", null));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success("Exit stats retrieved", exitService.getStats()));
    }

    private BigDecimal parseBd(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return new BigDecimal(value.toString());
    }
}
