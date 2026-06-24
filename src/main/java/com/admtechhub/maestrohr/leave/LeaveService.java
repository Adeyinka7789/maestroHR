package com.admtechhub.maestrohr.leave;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.notification.TermiiClient;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final TermiiClient termiiClient;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");

    // ==================== DTO CONVERSIONS ====================

    private LeaveRequestDTO toDto(LeaveRequest request) {
        if (request == null) return null;
        return LeaveRequestDTO.builder()
                .id(request.getId())
                .employeeId(request.getEmployee().getId())
                .employeeName(request.getEmployee().getFullName())
                .employeeNumber(request.getEmployee().getEmployeeNumber())
                .leaveTypeId(request.getLeaveType().getId())
                .leaveTypeName(request.getLeaveType().getName())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .daysRequested(request.getDaysRequested())
                .reason(request.getReason())
                .coverOfficerId(request.getCoverOfficer() != null ? request.getCoverOfficer().getId() : null)
                .coverOfficerName(request.getCoverOfficer() != null ? request.getCoverOfficer().getFullName() : null)
                .status(request.getStatus())
                .rejectionReason(request.getRejectionReason())
                .approvalComment(request.getApprovalComment())
                .approvedAt(request.getApprovedAt())
                .approvedByEmail(request.getApprovedBy() != null ? request.getApprovedBy().getEmail() : null)
                .createdAt(request.getCreatedAt() != null ? request.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    private List<LeaveRequestDTO> toDtoList(List<LeaveRequest> requests) {
        return requests.stream().map(this::toDto).toList();
    }

    private LeaveTypeDTO toDto(LeaveType type) {
        return LeaveTypeDTO.builder()
                .id(type.getId())
                .name(type.getName())
                .code(type.getCode())
                .maxDaysPerYear(type.getMaxDaysPerYear())
                .isPaid(type.getIsPaid())
                .requiresApproval(type.getRequiresApproval())
                .carryOverAllowed(type.getCarryOverAllowed())
                .maxCarryOverDays(type.getMaxCarryOverDays())
                .build();
    }

    // ==================== DEFAULT LEAVE TYPES ====================

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

    // ==================== LEAVE REQUEST OPERATIONS (RETURNS DTOs) ====================

    @Transactional
    public LeaveRequestDTO submitLeaveRequest(UUID employeeId, UUID leaveTypeId,
                                              LocalDate startDate, LocalDate endDate,
                                              String reason, UUID coverOfficerId) {

        String tenantIdStr = TenantContext.getCurrentTenant();
        UUID tenantId = UUID.fromString(tenantIdStr);

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        LeaveType leaveType = leaveTypeRepository.findById(leaveTypeId)
                .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));

        long daysRequested = calculateWorkingDays(startDate, endDate);
        int currentYear = LocalDate.now().getYear();

        LeaveBalance balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, currentYear)
                .orElse(null);

        if (balance != null && balance.getDaysRemaining() < daysRequested) {
            throw new IllegalStateException("Insufficient leave balance. " +
                    "Available: " + balance.getDaysRemaining() + ", Requested: " + daysRequested);
        }

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
        return toDto(saved);
    }

    @Transactional
    public LeaveRequestDTO approveLeaveRequest(UUID requestId, UUID approverUserId, String comment) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalStateException("Leave request is not pending");
        }

        User approver = userRepository.findById(approverUserId)
                .orElseThrow(() -> new IllegalArgumentException("Approver not found"));

        int currentYear = request.getStartDate().getYear();
        LeaveBalance balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeaveType().getId(),
                        currentYear)
                .orElse(null);

        if (balance == null) {
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

        // In-app notification
        notificationService.createInAppNotification(
                request.getEmployee().getEmail(),
                "LEAVE_APPROVED",
                "Leave request approved",
                "Your leave request from " + request.getStartDate() + " to " + request.getEndDate() + " has been approved.",
                "/leave"
        );

        // SMS Notification
        Employee employee = request.getEmployee();
        if (employee.getPhone() != null && !employee.getPhone().isEmpty()) {
            String formattedStartDate = request.getStartDate().format(DATE_FORMATTER);
            String formattedEndDate = request.getEndDate().format(DATE_FORMATTER);
            String smsMessage = String.format(
                    "MaestroHR: Your %s leave request from %s to %s has been APPROVED by %s.",
                    request.getLeaveType().getName(),
                    formattedStartDate,
                    formattedEndDate,
                    approver.getEmail().split("@")[0]
            );
            termiiClient.sendSms(employee.getPhone(), smsMessage);
        }

        log.info("Leave request {} approved, SMS sent to {}", requestId, employee.getPhone());
        return toDto(updated);
    }

    @Transactional
    public LeaveRequestDTO rejectLeaveRequest(UUID requestId, String reason) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new IllegalStateException("Leave request is not pending");
        }

        request.setStatus(LeaveStatus.REJECTED);
        request.setRejectionReason(reason);

        LeaveRequest updated = leaveRequestRepository.save(request);

        // In-app notification
        notificationService.createInAppNotification(
                request.getEmployee().getEmail(),
                "LEAVE_REJECTED",
                "Leave request rejected",
                "Your leave request from " + request.getStartDate() + " to " + request.getEndDate() + " was rejected. Reason: " + reason,
                "/leave"
        );

        // SMS Notification
        Employee employee = request.getEmployee();
        if (employee.getPhone() != null && !employee.getPhone().isEmpty()) {
            String smsMessage = String.format(
                    "MaestroHR: Your %s leave request from %s to %s was REJECTED. Reason: %s",
                    request.getLeaveType().getName(),
                    request.getStartDate(),
                    request.getEndDate(),
                    reason.length() > 100 ? reason.substring(0, 100) : reason
            );
            termiiClient.sendSms(employee.getPhone(), smsMessage);
        }

        log.info("Leave request {} rejected: {}, SMS sent to {}", requestId, reason, employee.getPhone());
        return toDto(updated);
    }

    // ==================== QUERY METHODS (RETURNS DTOs) ====================

    @Transactional(readOnly = true)
    public List<LeaveTypeDTO> getAllLeaveTypes() {
        List<LeaveType> types = leaveTypeRepository.findAll();
        return types.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Page<LeaveRequestDTO> getAllLeaveRequests(Pageable pageable) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Page<LeaveRequest> page = leaveRequestRepository.findByEmployeeTenantId(tenantId, pageable);
        return page.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDTO> getPendingRequests() {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        List<LeaveRequest> pending = leaveRequestRepository.findByTenantIdAndStatus(tenantId, LeaveStatus.PENDING);
        return toDtoList(pending);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDTO> getEmployeeLeaveRequests(UUID employeeId) {
        List<LeaveRequest> requests = leaveRequestRepository.findByEmployeeId(employeeId, PageRequest.of(0, 5));
        return toDtoList(requests);
    }

    // ==================== LEAVE BALANCE ====================

    @Transactional(readOnly = true)
    public LeaveBalance getLeaveBalance(UUID employeeId, UUID leaveTypeId, Integer year) {
        return leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                .orElse(null);
    }

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

    /**
     * Returns the total number of working days (Mon–Sat) covered by approved
     * unpaid leave requests for this employee that overlap the payroll period.
     * Leave requests that straddle a period boundary are clamped so only the
     * portion inside the period is counted.
     */
    @Transactional(readOnly = true)
    public int getUnpaidLeaveDays(UUID employeeId, LocalDate periodStart, LocalDate periodEnd) {
        List<LeaveRequest> unpaidLeaves = leaveRequestRepository
                .findApprovedUnpaidLeavesInRange(employeeId, periodStart, periodEnd);
        int totalDays = 0;
        for (LeaveRequest leave : unpaidLeaves) {
            LocalDate effectiveStart = leave.getStartDate().isBefore(periodStart) ? periodStart : leave.getStartDate();
            LocalDate effectiveEnd   = leave.getEndDate().isAfter(periodEnd)       ? periodEnd   : leave.getEndDate();
            totalDays += (int) calculateWorkingDays(effectiveStart, effectiveEnd);
        }
        return totalDays;
    }

    private long calculateWorkingDays(LocalDate startDate, LocalDate endDate) {
        long days = 0;
        LocalDate date = startDate;
        while (!date.isAfter(endDate)) {
            if (date.getDayOfWeek().getValue() != 7) {
                days++;
            }
            date = date.plusDays(1);
        }
        return days;
    }
}