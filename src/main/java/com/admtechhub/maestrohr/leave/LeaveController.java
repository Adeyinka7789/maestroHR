package com.admtechhub.maestrohr.leave;

import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
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
public class LeaveController {

    private final LeaveService leaveService;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
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
    public ResponseEntity<ApiResponse<List<LeaveType>>> getLeaveTypes() {
        List<LeaveType> types = leaveTypeRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success("Leave types retrieved", types));
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<LeaveRequest>>> getAllLeaveRequests() {
        List<LeaveRequest> requests = leaveRequestRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success("All leave requests retrieved", requests));
    }

    /**
     * Submit leave request
     * POST /api/leave/requests
     */
    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'DEPT_MANAGER', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<LeaveRequest>> submitLeaveRequest(
            @RequestParam UUID employeeId,
            @RequestParam UUID leaveTypeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam String reason,
            @RequestParam(required = false) UUID coverOfficerId) {

        LeaveRequest request = leaveService.submitLeaveRequest(
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
    public ResponseEntity<ApiResponse<List<LeaveRequest>>> getEmployeeLeaveRequests(@PathVariable UUID employeeId) {
        List<LeaveRequest> requests = leaveRequestRepository.findByEmployeeId(employeeId);
        return ResponseEntity.ok(ApiResponse.success("Leave requests retrieved", requests));
    }

    /**
     * Get pending leave requests
     * GET /api/leave/requests/pending
     */
    @GetMapping("/requests/pending")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<List<LeaveRequest>>> getPendingRequests() {
        List<LeaveRequest> requests = leaveRequestRepository.findByStatus(LeaveStatus.PENDING);
        return ResponseEntity.ok(ApiResponse.success("Pending leave requests", requests));
    }

    /**
     * Approve leave request
     * POST /api/leave/requests/{requestId}/approve
     */
    @PostMapping("/requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<LeaveRequest>> approveRequest(
            @PathVariable UUID requestId,
            @RequestParam(required = false) String comment) {

        UUID approverId = getCurrentUserId();
        LeaveRequest request = leaveService.approveLeaveRequest(requestId, approverId, comment);
        return ResponseEntity.ok(ApiResponse.success("Leave request approved", request));
    }

    /**
     * Reject leave request
     * POST /api/leave/requests/{requestId}/reject
     */
    @PostMapping("/requests/{requestId}/reject")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<LeaveRequest>> rejectRequest(
            @PathVariable UUID requestId,
            @RequestParam String reason) {

        LeaveRequest request = leaveService.rejectLeaveRequest(requestId, reason);
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