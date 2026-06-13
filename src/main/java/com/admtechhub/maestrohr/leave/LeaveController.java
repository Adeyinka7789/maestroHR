package com.admtechhub.maestrohr.leave;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/leave")
@RequiredArgsConstructor
@RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
public class LeaveController {

    private final LeaveService leaveService;
    private final UserRepository userRepository;

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email))
                .getId();
    }

    /**
     * Get all leave types
     * GET /api/leave/types
     */
    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<LeaveTypeDTO>>> getLeaveTypes() {
        List<LeaveTypeDTO> types = leaveService.getAllLeaveTypes();
        return ResponseEntity.ok(ApiResponse.success("Leave types retrieved", types));
    }

    /**
     * Get all leave requests (paginated, tenant‑filtered)
     * GET /api/leave/requests?page=0&size=20
     */
    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<LeaveRequestDTO>>> getAllLeaveRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<LeaveRequestDTO> pageResult = leaveService.getAllLeaveRequests(pageable);
        return ResponseEntity.ok(ApiResponse.success("Leave requests retrieved", pageResult));
    }

    /**
     * Submit leave request
     * POST /api/leave/requests
     */
    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'DEPT_MANAGER', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> submitLeaveRequest(
            @RequestParam UUID employeeId,
            @RequestParam UUID leaveTypeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam String reason,
            @RequestParam(required = false) UUID coverOfficerId) {

        LeaveRequestDTO request = leaveService.submitLeaveRequest(
                employeeId, leaveTypeId, startDate, endDate, reason, coverOfficerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Leave request submitted", request));
    }

    /**
     * Get leave requests for an employee
     * GET /api/leave/requests/employee/{employeeId}
     */
    @GetMapping("/requests/employee/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<List<LeaveRequestDTO>>> getEmployeeLeaveRequests(@PathVariable UUID employeeId) {
        List<LeaveRequestDTO> requests = leaveService.getEmployeeLeaveRequests(employeeId);
        return ResponseEntity.ok(ApiResponse.success("Leave requests retrieved", requests));
    }

    /**
     * Get pending leave requests
     * GET /api/leave/requests/pending
     */
    @GetMapping("/requests/pending")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<List<LeaveRequestDTO>>> getPendingRequests() {
        List<LeaveRequestDTO> pending = leaveService.getPendingRequests();
        return ResponseEntity.ok(ApiResponse.success("Pending leave requests", pending));
    }

    /**
     * Approve leave request
     * POST /api/leave/requests/{requestId}/approve
     */
    @PostMapping("/requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> approveRequest(
            @PathVariable UUID requestId,
            @RequestParam(required = false) String comment) {

        UUID approverId = getCurrentUserId();
        LeaveRequestDTO request = leaveService.approveLeaveRequest(requestId, approverId, comment);
        return ResponseEntity.ok(ApiResponse.success("Leave request approved", request));
    }

    /**
     * Reject leave request
     * POST /api/leave/requests/{requestId}/reject
     */
    @PostMapping("/requests/{requestId}/reject")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> rejectRequest(
            @PathVariable UUID requestId,
            @RequestParam String reason) {

        LeaveRequestDTO request = leaveService.rejectLeaveRequest(requestId, reason);
        return ResponseEntity.ok(ApiResponse.success("Leave request rejected", request));
    }

    /**
     * Get leave balance
     * GET /api/leave/balance?employeeId={employeeId}&leaveTypeId={leaveTypeId}&year={year}
     */
    @GetMapping("/balance")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<LeaveBalance>> getLeaveBalance(
            @RequestParam UUID employeeId,
            @RequestParam UUID leaveTypeId,
            @RequestParam Integer year) {

        LeaveBalance balance = leaveService.getLeaveBalance(employeeId, leaveTypeId, year);
        return ResponseEntity.ok(ApiResponse.success("Leave balance retrieved", balance));
    }
}