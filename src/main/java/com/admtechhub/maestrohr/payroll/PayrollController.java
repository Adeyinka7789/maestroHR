package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payroll.dto.PayrollRunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollRunService payrollRunService;
    private final UserRepository userRepository;
    private final DisbursementService disbursementService;
    private final PayrollEntryRepository payrollEntryRepository;

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email))
                .getId();
    }

    // SPECIFIC ENDPOINTS FIRST (before path variables)
    @GetMapping("/pending-count")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getPendingApprovalsCount() {
        long count = payrollRunService.getPendingApprovalsCount();
        return ResponseEntity.ok(ApiResponse.success("Pending count retrieved", Map.of("count", count)));
    }

    @PostMapping("/initiate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> initiatePayroll(
            @RequestParam Integer month,
            @RequestParam Integer year) {
        UUID initiatedByUserId = getCurrentUserId();
        PayrollRunResponse response = payrollRunService.initiatePayroll(month, year, initiatedByUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Payroll initiated successfully", response));
    }

    @PostMapping("/{payrollRunId}/compute")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> computePayroll(@PathVariable UUID payrollRunId) {
        PayrollRunResponse response = payrollRunService.computePayroll(payrollRunId);
        return ResponseEntity.ok(ApiResponse.success("Payroll computed successfully", response));
    }

    @PostMapping("/{payrollRunId}/submit")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> submitForApproval(@PathVariable UUID payrollRunId) {
        PayrollRunResponse response = payrollRunService.submitForApproval(payrollRunId);
        return ResponseEntity.ok(ApiResponse.success("Payroll submitted for approval", response));
    }

    @PostMapping("/{payrollRunId}/approve")
    @PreAuthorize("hasRole('FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> approvePayroll(@PathVariable UUID payrollRunId) {
        UUID approvedByUserId = getCurrentUserId();
        PayrollRunResponse response = payrollRunService.approvePayroll(payrollRunId, approvedByUserId);
        return ResponseEntity.ok(ApiResponse.success("Payroll approved successfully", response));
    }

    @PostMapping("/{payrollRunId}/reject")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> rejectPayroll(
            @PathVariable UUID payrollRunId,
            @RequestParam String reason) {
        PayrollRunResponse response = payrollRunService.rejectPayroll(payrollRunId, reason);
        return ResponseEntity.ok(ApiResponse.success("Payroll rejected", response));
    }

    @GetMapping("/{payrollRunId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> getPayrollRun(@PathVariable UUID payrollRunId) {
        PayrollRunResponse response = payrollRunService.getPayrollRunResponse(payrollRunId);
        return ResponseEntity.ok(ApiResponse.success("Payroll run retrieved", response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<List<PayrollRunResponse>>> getAllPayrollRuns() {
        List<PayrollRunResponse> responses = payrollRunService.getAllPayrollRunResponses();
        return ResponseEntity.ok(ApiResponse.success("Payroll runs retrieved", responses));
    }

    @GetMapping("/{payrollRunId}/entries")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<List<PayrollRunResponse.PayrollEntryResponse>>> getPayrollEntries(@PathVariable UUID payrollRunId) {
        List<PayrollRunResponse.PayrollEntryResponse> entries = payrollRunService.getPayrollEntryResponses(payrollRunId);
        return ResponseEntity.ok(ApiResponse.success("Payroll entries retrieved", entries));
    }

    @GetMapping("/{payrollRunId}/summary")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPayrollSummary(@PathVariable UUID payrollRunId) {
        PayrollRunResponse payrollRun = payrollRunService.getPayrollRunResponse(payrollRunId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("payrollRunId", payrollRun.getId());
        summary.put("period", payrollRun.getPeriod());
        summary.put("status", payrollRun.getStatus().toString());
        summary.put("totalEmployees", payrollRun.getEntries().size());
        summary.put("totalGross", payrollRun.getTotalGross());
        summary.put("totalNet", payrollRun.getTotalNet());
        summary.put("totalPaye", payrollRun.getTotalPaye());
        summary.put("totalPension", payrollRun.getTotalPensionEmployee());
        summary.put("totalNhf", payrollRun.getTotalNhf());
        summary.put("initiatedBy", payrollRun.getInitiatedBy().getEmail());
        summary.put("approvedBy", payrollRun.getApprovedBy() != null ? payrollRun.getApprovedBy().getEmail() : null);
        summary.put("approvedAt", payrollRun.getApprovedAt());
        summary.put("rejectionReason", payrollRun.getRejectionReason());

        return ResponseEntity.ok(ApiResponse.success("Payroll summary retrieved", summary));
    }

    @PostMapping("/{payrollRunId}/disburse")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<PayrollRunResponse>> disburseSalaries(@PathVariable UUID payrollRunId) {
        PayrollRun payrollRun = disbursementService.disburseSalaries(payrollRunId);
        return ResponseEntity.ok(ApiResponse.success("Salary disbursement initiated",
                payrollRunService.getPayrollRunResponse(payrollRunId)));
    }

    /**
     * Get pending approvals count
     * GET /api/payroll/pending-approvals
     */
    @GetMapping("/pending-approvals")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<List<PayrollRunResponse>>> getPendingApprovals() {
        List<PayrollRunResponse> responses = payrollRunService.getPendingApprovals();
        return ResponseEntity.ok(ApiResponse.success("Pending approvals retrieved", responses));
    }

    @GetMapping("/employee/{employeeId}/recent")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'HR_ADMIN', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecentPayslips(
            @PathVariable UUID employeeId,
            @RequestParam(defaultValue = "5") int limit) {

        List<PayrollEntry> entries = payrollEntryRepository.findByEmployeeIdOrderByPayrollRunCreatedAtDesc(employeeId);

        List<Map<String, Object>> result = entries.stream()
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("period", entry.getPayrollRun().getPeriod());
                    map.put("netSalary", entry.getNetSalary());
                    map.put("payrollRunId", entry.getPayrollRun().getId());
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("Recent payslips retrieved", result));
    }
}