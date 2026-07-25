package com.admtechhub.maestrohr.adjustment;

import com.admtechhub.maestrohr.adjustment.AdjustmentDTOs.*;
import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the Statutory Reimbursements & Deductions manager. HR_ADMIN / FINANCE_OFFICER
 * (and SUPER_ADMIN) manage the adjustment-type catalogue and log per-employee adjustments that the
 * next payroll run consumes. Tenant isolation is enforced by RLS on the underlying tables.
 */
@RestController
@RequestMapping("/api/payroll-adjustments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
public class PayrollAdjustmentController {

    private final PayrollAdjustmentService service;

    // ── Types ────────────────────────────────────────────────────────────────────

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<TypeView>>> listTypes(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success("Adjustment types", service.listTypes(includeInactive)));
    }

    @PostMapping("/types")
    public ResponseEntity<ApiResponse<TypeView>> createType(@RequestBody CreateTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Adjustment type created", service.createType(request)));
    }

    @PostMapping("/types/{id}/toggle")
    public ResponseEntity<ApiResponse<TypeView>> toggleType(
            @PathVariable UUID id, @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.success("Adjustment type updated", service.setTypeActive(id, active)));
    }

    // ── Adjustments ──────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<List<AdjustmentView>>> listForPeriod(
            @RequestParam int month, @RequestParam int year) {
        return ResponseEntity.ok(ApiResponse.success("Adjustments", service.listForPeriod(month, year)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdjustmentView>> create(@RequestBody CreateAdjustmentRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Adjustment logged", service.create(request, email)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ResponseEntity.ok(ApiResponse.success("Adjustment cancelled", null));
    }
}
