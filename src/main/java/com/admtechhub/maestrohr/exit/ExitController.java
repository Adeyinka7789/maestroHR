package com.admtechhub.maestrohr.exit;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getExitRequests() {
        return ResponseEntity.ok(ApiResponse.success("Exit requests retrieved", exitService.getAllExitRequests()));
    }

    @GetMapping("/requests/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getExitRequest(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Exit request details", exitService.getExitRequestById(id)));
    }

    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ExitRequest>> createExitRequest(@RequestBody Map<String, Object> payload) {
        UUID employeeId = UUID.fromString((String) payload.get("employeeId"));
        String exitType = (String) payload.get("exitType");
        LocalDate lastWorkingDay = LocalDate.parse((String) payload.get("lastWorkingDay"));
        String reason = (String) payload.get("reason");
        ExitRequest request = exitService.createExitRequest(employeeId, exitType, lastWorkingDay, reason);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Exit request created", request));
    }

    @PutMapping("/clearance/{exitRequestId}/{itemId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updateClearance(@PathVariable UUID exitRequestId,
                                                             @PathVariable UUID itemId,
                                                             @RequestParam String status) {
        exitService.updateClearanceItem(exitRequestId, itemId, status, "admin@example.com"); // replace with actual user
        return ResponseEntity.ok(ApiResponse.success("Clearance updated", null));
    }

    @PostMapping("/settlement/{exitRequestId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> calculateSettlement(@PathVariable UUID exitRequestId,
                                                                 @RequestBody Map<String, Object> payload) {
        BigDecimal unpaidSalary = new BigDecimal(payload.get("unpaidSalary").toString());
        BigDecimal accruedLeave = new BigDecimal(payload.get("accruedLeave").toString());
        BigDecimal severancePay = new BigDecimal(payload.get("severancePay").toString());
        BigDecimal otherDeductions = new BigDecimal(payload.get("otherDeductions").toString());
        exitService.calculateSettlement(exitRequestId, unpaidSalary, accruedLeave, severancePay, otherDeductions);
        return ResponseEntity.ok(ApiResponse.success("Settlement calculated", null));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success("Exit stats retrieved", exitService.getStats()));
    }
}