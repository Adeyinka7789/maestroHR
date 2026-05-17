package com.admtechhub.maestrohr.reporting;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportsApiController {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getStats() {
        // Get current tenant ID from context
        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = UUID.fromString(tenantIdStr);

        Map<String, Long> stats = new HashMap<>();

        // Count employees for this tenant only
        long employeeCount = employeeRepository.countByTenantId(tenantId);
        stats.put("employees", employeeCount);

        // Count departments for this tenant only
        long departmentCount = departmentRepository.countByTenantId(tenantId);
        stats.put("departments", departmentCount);

        // Count pending leave requests for this tenant only
        long pendingLeaveCount = leaveRequestRepository.countByTenantIdAndStatus(tenantId, LeaveStatus.PENDING);
        stats.put("pendingLeave", pendingLeaveCount);

        return ResponseEntity.ok(ApiResponse.success("Stats retrieved", stats));
    }
}