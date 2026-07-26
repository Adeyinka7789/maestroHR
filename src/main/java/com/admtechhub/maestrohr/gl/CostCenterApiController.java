package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Minimal REST surface for cost centers — backs the cost-center dropdown on the employee
 * create/edit forms. Management (create/update/toggle) lives on the HTMX page
 * ({@link CostCenterController}).
 */
@RestController
@RequestMapping("/api/cost-centers")
@RequiredArgsConstructor
public class CostCenterApiController {

    private final CostCenterService costCenterService;

    /** Active cost centers for the current tenant, for form selection. */
    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<CostCenterView>>> listActive() {
        return ResponseEntity.ok(ApiResponse.success("Cost centers", costCenterService.listActive()));
    }
}
