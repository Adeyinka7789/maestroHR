package com.admtechhub.maestrohr.leave;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * Create default leave types for a new tenant
     */
    @Transactional
    public void createDefaultLeaveTypes(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        LeaveType[] defaultTypes = {
                createLeaveType(tenant, "Annual Leave", "ANNUAL", 20, true, true, true, 5),
                createLeaveType(tenant, "Sick Leave", "SICK", 12, true, true, false, 0),
                createLeaveType(tenant, "Maternity Leave", "MATERNITY", 60, true, true, false, 0),
                createLeaveType(tenant, "Paternity Leave", "PATERNITY", 14, true, true, false, 0),
                createLeaveType(tenant, "Casual Leave", "CASUAL", 5, true, true, false, 0),
                createLeaveType(tenant, "Unpaid Leave", "UNPAID", 30, false, true, false, 0)
        };

        for (LeaveType type : defaultTypes) {
            if (!leaveTypeRepository.existsByCode(type.getCode())) {
                leaveTypeRepository.save(type);
            }
        }
        log.info("Default leave types created for tenant: {}", tenantId);
    }

    private LeaveType createLeaveType(Tenant tenant, String name, String code,
                                      Integer maxDays, Boolean isPaid,
                                      Boolean requiresApproval,
                                      Boolean carryOverAllowed,
                                      Integer maxCarryOver) {
        return LeaveType.builder()
                .tenant(tenant)
                .name(name)
                .code(code)
                .maxDaysPerYear(maxDays)
                .isPaid(isPaid)
                .requiresApproval(requiresApproval)
                .carryOverAllowed(carryOverAllowed)
                .maxCarryOverDays(maxCarryOver)
                .build();
    }

    /**
     * Submit a leave request
     */
    @Transactional
    public LeaveRequest submitLeaveRequest(UUID employeeId, UUID leaveTypeId,
                                           LocalDate startDate, LocalDate endDate,
                                           String reason, UUID coverOfficerId) {

        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = UUID.fromString(tenantIdStr);

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        LeaveType leaveType = leaveTypeRepository.findById(leaveTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));

        // Calculate days requested (exclude Sundays for MVP)
        long daysRequested = calculateWorkingDays(startDate, endDate);

        // Check available balance
        int currentYear = LocalDate.now().getYear();
        LeaveBalance balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, currentYear)
                .orElse(null);

        if (balance != null && balance.getDaysRemaining() < daysRequested) {
            throw new IllegalStateException("Insufficient leave balance. " +
                    "Available: " + balance.getDaysRemaining() + ", Requested: " + daysRequested);
        }

        // Check for overlapping approved leaves
        List<LeaveRequest> overlapping = leaveRequestRepository
                .findApprovedLeavesInDateRange(employeeId, startDate, endDate);
        if (!overlapping.isEmpty()) {
            throw new IllegalStateException("You already have approved leave during this period");
        }

        Employee coverOfficer = coverOfficerId != null ?
                employeeRepository.findById(coverOfficerId).orElse(null) : null;

        LeaveRequest request = LeaveRequest.builder()
                .tenant(employee.getTenant())
                .employee(employee)
                .leaveType(leaveType)
                .startDate(startDate)
                .endDate(endDate)
                .daysRequested((int) daysRequested)
                .reason(reason)
                .coverOfficer(coverOfficer)
                .status(LeaveStatus.PENDING)
                .build();

        LeaveRequest saved = leaveRequestRepository.save(request);
        notificationService.createInAppNotification(
                employee.getEmail(),
                "LEAVE_SUBMITTED",
                "Leave request submitted",
                "Your " + leaveType.getName() + " request from " + startDate + " to " + endDate + " has been submitted.",
                "/leave"
        );
        log.info("Leave request submitted: {} for employee {}", saved.getId(), employeeId);

        return saved;
    }

    /**
     * Approve leave request
     */
    @Transactional
    public LeaveRequest approveLeaveRequest(UUID requestId, UUID approverUserId, String comment) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalStateException("Leave request is not pending");
        }

        User approver = userRepository.findById(approverUserId)
                .orElseThrow(() -> new IllegalArgumentException("Approver not found"));

        // Deduct from balance
        int currentYear = request.getStartDate().getYear();
        LeaveBalance balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeaveType().getId(),
                        currentYear)
                .orElse(null);

        if (balance == null) {
            // Create balance if it doesn't exist
            balance = createLeaveBalance(request.getEmployee(), request.getLeaveType(), currentYear);
        }

        leaveBalanceRepository.deductLeaveDays(
                request.getEmployee().getId(),
                request.getLeaveType().getId(),
                currentYear,
                request.getDaysRequested()
        );

        request.setStatus(LeaveStatus.APPROVED);
        request.setApprovedBy(approver);
        request.setApprovalComment(comment);
        request.setApprovedAt(LocalDateTime.now());

        LeaveRequest updated = leaveRequestRepository.save(request);
        notificationService.createInAppNotification(
                request.getEmployee().getEmail(),
                "LEAVE_APPROVED",
                "Leave request approved",
                "Your leave request from " + request.getStartDate() + " to " + request.getEndDate() + " has been approved.",
                "/leave"
        );
        log.info("Leave request {} approved", requestId);

        return updated;
    }

    /**
     * Reject leave request
     */
    @Transactional
    public LeaveRequest rejectLeaveRequest(UUID requestId, String reason) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalStateException("Leave request is not pending");
        }

        request.setStatus(LeaveStatus.REJECTED);
        request.setRejectionReason(reason);

        LeaveRequest updated = leaveRequestRepository.save(request);
        notificationService.createInAppNotification(
                request.getEmployee().getEmail(),
                "LEAVE_REJECTED",
                "Leave request rejected",
                "Your leave request from " + request.getStartDate() + " to " + request.getEndDate() + " was rejected. Reason: " + reason,
                "/leave"
        );
        log.info("Leave request {} rejected: {}", requestId, reason);

        return updated;
    }

    /**
     * Get leave balance for an employee
     */
    @Transactional(readOnly = true)
    public LeaveBalance getLeaveBalance(UUID employeeId, UUID leaveTypeId, Integer year) {
        return leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                .orElse(null);
    }

    /**
     * Initialize leave balance for a new employee
     */
    @Transactional
    public void initializeLeaveBalance(Employee employee, LeaveType leaveType, Integer year) {
        if (!leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                employee.getId(), leaveType.getId(), year).isPresent()) {
            createLeaveBalance(employee, leaveType, year);
        }
    }

    private LeaveBalance createLeaveBalance(Employee employee, LeaveType leaveType, Integer year) {
        LeaveBalance balance = LeaveBalance.builder()
                .tenant(employee.getTenant())
                .employee(employee)
                .leaveType(leaveType)
                .year(year)
                .totalDaysEntitled(leaveType.getMaxDaysPerYear())
                .daysTaken(0)
                .daysCarriedOver(0)
                .daysRemaining(leaveType.getMaxDaysPerYear())
                .build();

        return leaveBalanceRepository.save(balance);
    }

    private long calculateWorkingDays(LocalDate startDate, LocalDate endDate) {
        long days = 0;
        LocalDate date = startDate;
        while (!date.isAfter(endDate)) {
            if (date.getDayOfWeek().getValue() != 7) { // Exclude Sunday
                days++;
            }
            date = date.plusDays(1);
        }
        return days;
    }
}
