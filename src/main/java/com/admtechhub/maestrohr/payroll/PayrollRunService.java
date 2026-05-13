package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.payroll.dto.PayrollRunResponse;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollRunService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PayrollEngine payrollEngine;
    private final NotificationService notificationService;

    private static final int DEFAULT_WORKING_DAYS = 22;

    /**
     * Initiate a new payroll run
     */
    @Transactional
    public PayrollRunResponse initiatePayroll(Integer month, Integer year, UUID initiatedByUserId) {
        UUID tenantId = currentTenantId();

        log.info("Initiating payroll for {}-{} for tenant: {}", month, year, tenantId);

        if (payrollRunRepository.existsByTenant_IdAndPayrollMonthAndPayrollYear(tenantId, month, year)) {
            throw new IllegalStateException("Payroll for " + month + "/" + year + " already exists");
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Tenant not found: " + tenantId));

        User initiatedBy = userRepository.findById(initiatedByUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + initiatedByUserId));

        PayrollRun payrollRun = PayrollRun.builder()
                .tenant(tenant)
                .payrollMonth(month)
                .payrollYear(year)
                .status(PayrollStatus.DRAFT)
                .initiatedBy(initiatedBy)
                .build();

        PayrollRun saved = payrollRunRepository.save(payrollRun);
        notificationService.createInAppNotification(
                initiatedBy.getEmail(),
                "PAYROLL_INITIATED",
                "Payroll initiated",
                "Payroll run " + saved.getPeriod() + " has been created in draft state.",
                "/payroll/" + saved.getId()
        );
        log.info("Payroll run initiated with ID: {}", saved.getId());

        return toResponse(saved);
    }

    /**
     * Compute payroll for a run (generate entries)
     */
    @Transactional
    public PayrollRunResponse computePayroll(UUID payrollRunId) {
        UUID tenantId = currentTenantId();

        log.info("Computing payroll for run: {}", payrollRunId);

        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (payrollRun.getStatus() != PayrollStatus.DRAFT) {
            throw new IllegalStateException("Payroll must be in DRAFT status to compute");
        }

        List<Employee> activeEmployees = employeeRepository.findByStatus(EmployeeStatus.ACTIVE).stream()
                .filter(employee -> employee.getTenant() != null && tenantId.equals(employee.getTenant().getId()))
                .toList();

        if (activeEmployees.isEmpty()) {
            throw new IllegalStateException("No active employees found for payroll");
        }

        int workingDays = getWorkingDays(payrollRun.getPayrollMonth(), payrollRun.getPayrollYear());

        List<PayrollEntry> entries = new ArrayList<>();
        long totalGross = 0;
        long totalNet = 0;
        long totalPaye = 0;
        long totalPensionEmployee = 0;
        long totalPensionEmployer = 0;
        long totalNhf = 0;

        for (Employee employee : activeEmployees) {
            int daysWorked = workingDays;

            PayrollEngine.PayrollResult result = payrollEngine.calculateEmployeePayroll(
                    employee, daysWorked, workingDays);

            PayrollEntry entry = PayrollEntry.builder()
                    .tenant(employee.getTenant())
                    .payrollRun(payrollRun)
                    .employee(employee)
                    .basicSalary(result.getBasicSalary())
                    .housingAllowance(result.getHousingAllowance())
                    .transportAllowance(result.getTransportAllowance())
                    .otherAllowances(result.getOtherAllowances())
                    .grossSalary(result.getGrossSalary())
                    .pensionEmployee(result.getPensionEmployee())
                    .pensionEmployer(result.getPensionEmployer())
                    .nhfDeduction(result.getNhfDeduction())
                    .payeTax(result.getPayeTax())
                    .otherDeductions(result.getOtherDeductions())
                    .netSalary(result.getNetSalary())
                    .daysWorked(result.getDaysWorked())
                    .workingDays(result.getWorkingDays())
                    .isProrated(result.getIsProrated())
                    .transferStatus(TransferStatus.PENDING)
                    .payslipGenerated(false)
                    .build();

            entries.add(entry);

            totalGross += result.getGrossSalary();
            totalNet += result.getNetSalary();
            totalPaye += result.getPayeTax();
            totalPensionEmployee += result.getPensionEmployee();
            totalPensionEmployer += result.getPensionEmployer();
            totalNhf += result.getNhfDeduction();
        }

        payrollEntryRepository.saveAll(entries);

        payrollRun.setTotalGross(totalGross);
        payrollRun.setTotalNet(totalNet);
        payrollRun.setTotalPaye(totalPaye);
        payrollRun.setTotalPensionEmployee(totalPensionEmployee);
        payrollRun.setTotalPensionEmployer(totalPensionEmployer);
        payrollRun.setTotalNhf(totalNhf);

        PayrollRun updated = payrollRunRepository.save(payrollRun);

        log.info("Payroll computation complete: {} entries, Total Gross: {}", entries.size(), totalGross);

        return toResponse(updated);
    }

    /**
     * Submit payroll for approval
     */
    @Transactional
    public PayrollRunResponse submitForApproval(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (!payrollRun.canSubmit()) {
            throw new IllegalStateException("Payroll cannot be submitted. Current status: " + payrollRun.getStatus());
        }

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId);
        if (entries.isEmpty()) {
            throw new IllegalStateException("Cannot submit empty payroll. Run compute first.");
        }

        payrollRun.setStatus(PayrollStatus.PENDING_APPROVAL);
        PayrollRun updated = payrollRunRepository.save(payrollRun);
        notificationService.createInAppNotification(
                payrollRun.getInitiatedBy().getEmail(),
                "PAYROLL_SUBMITTED",
                "Payroll submitted",
                "Payroll run " + payrollRun.getPeriod() + " has been submitted for approval.",
                "/payroll/" + payrollRunId
        );
        log.info("Payroll run {} submitted for approval", payrollRunId);

        return toResponse(updated);
    }

    /**
     * Approve payroll
     */
    @Transactional
    public PayrollRunResponse approvePayroll(UUID payrollRunId, UUID approvedByUserId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (!payrollRun.canApprove()) {
            throw new IllegalStateException("Payroll cannot be approved. Current status: " + payrollRun.getStatus());
        }

        User approvedBy = userRepository.findById(approvedByUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + approvedByUserId));

        payrollRun.setStatus(PayrollStatus.APPROVED);
        // After setting status to APPROVED, trigger notifications
        for (PayrollEntry entry : payrollRun.getEntries()) {
            notificationService.sendPayslipNotification(
                    entry,
                    entry.getEmployee(),
                    payrollRun.getPeriod()
            );
        }
        payrollRun.setApprovedBy(approvedBy);
        payrollRun.setApprovedAt(LocalDateTime.now());

        PayrollRun updated = payrollRunRepository.save(payrollRun);
        notificationService.createInAppNotification(
                approvedBy.getEmail(),
                "PAYROLL_APPROVED",
                "Payroll approved",
                "Payroll run " + payrollRun.getPeriod() + " has been approved.",
                "/payroll/" + payrollRunId
        );
        if (payrollRun.getInitiatedBy() != null && !payrollRun.getInitiatedBy().getEmail().equalsIgnoreCase(approvedBy.getEmail())) {
            notificationService.createInAppNotification(
                    payrollRun.getInitiatedBy().getEmail(),
                    "PAYROLL_APPROVED",
                    "Payroll approved",
                    "Payroll run " + payrollRun.getPeriod() + " was approved by " + approvedBy.getEmail() + ".",
                    "/payroll/" + payrollRunId
            );
        }
        log.info("Payroll run {} approved by {}", payrollRunId, approvedByUserId);

        return toResponse(updated);
    }

    /**
     * Reject payroll
     */
    @Transactional
    public PayrollRunResponse rejectPayroll(UUID payrollRunId, String reason) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (!payrollRun.canReject()) {
            throw new IllegalStateException("Payroll cannot be rejected. Current status: " + payrollRun.getStatus());
        }

        payrollRun.setStatus(PayrollStatus.REJECTED);
        payrollRun.setRejectionReason(reason);

        PayrollRun updated = payrollRunRepository.save(payrollRun);
        if (payrollRun.getInitiatedBy() != null) {
            notificationService.createInAppNotification(
                    payrollRun.getInitiatedBy().getEmail(),
                    "PAYROLL_REJECTED",
                    "Payroll rejected",
                    "Payroll run " + payrollRun.getPeriod() + " was rejected. Reason: " + reason,
                    "/payroll/" + payrollRunId
            );
        }
        log.info("Payroll run {} rejected: {}", payrollRunId, reason);

        return toResponse(updated);
    }

    /**
     * Get payroll run by ID
     */
    @Transactional(readOnly = true)
    public PayrollRunResponse getPayrollRunResponse(UUID id) {
        PayrollRun payrollRun = payrollRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + id));
        return toResponse(payrollRun);
    }

    /**
     * Get all payroll runs for tenant
     */
    @Transactional(readOnly = true)
    public List<PayrollRunResponse> getAllPayrollRunResponses() {
        List<PayrollRun> payrollRuns = payrollRunRepository.findAllByTenant_IdOrderByCreatedAtDesc(currentTenantId());
        return payrollRuns.stream().map(this::toResponse).toList();
    }

    /**
     * Get entries for a payroll run
     */
    @Transactional(readOnly = true)
    public List<PayrollEntry> getPayrollEntries(UUID payrollRunId) {
        return payrollEntryRepository.findByPayrollRunId(payrollRunId);
    }

    private UUID currentTenantId() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalStateException("No tenant context available for payroll operation");
        }
        return UUID.fromString(tenantIdStr);
    }

    private int getWorkingDays(int month, int year) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate lastDay = firstDay.plusMonths(1).minusDays(1);

        int workingDays = 0;
        for (LocalDate date = firstDay; !date.isAfter(lastDay); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() != 7) {
                workingDays++;
            }
        }
        return workingDays;
    }

    private PayrollRunResponse toResponse(PayrollRun payrollRun) {
        // Convert OffsetDateTime to LocalDateTime
        LocalDateTime createdAt = payrollRun.getCreatedAt() != null ?
                payrollRun.getCreatedAt().toLocalDateTime() : null;
        LocalDateTime updatedAt = payrollRun.getUpdatedAt() != null ?
                payrollRun.getUpdatedAt().toLocalDateTime() : null;
        LocalDateTime approvedAt = payrollRun.getApprovedAt();

        List<PayrollRunResponse.PayrollEntryResponse> entryResponses = payrollRun.getEntries().stream()
                .map(entry -> PayrollRunResponse.PayrollEntryResponse.builder()
                        .id(entry.getId())
                        .basicSalary(entry.getBasicSalary())
                        .housingAllowance(entry.getHousingAllowance())
                        .transportAllowance(entry.getTransportAllowance())
                        .otherAllowances(entry.getOtherAllowances())
                        .grossSalary(entry.getGrossSalary())
                        .pensionEmployee(entry.getPensionEmployee())
                        .pensionEmployer(entry.getPensionEmployer())
                        .nhfDeduction(entry.getNhfDeduction())
                        .payeTax(entry.getPayeTax())
                        .netSalary(entry.getNetSalary())
                        .employeeName(entry.getEmployee().getFullName())
                        .employeeNumber(entry.getEmployee().getEmployeeNumber())
                        .build())
                .toList();

        return PayrollRunResponse.builder()
                .id(payrollRun.getId())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .tenant(PayrollRunResponse.TenantDto.builder()
                        .id(payrollRun.getTenant().getId())
                        .companyName(payrollRun.getTenant().getCompanyName())
                        .build())
                .payrollMonth(payrollRun.getPayrollMonth())
                .payrollYear(payrollRun.getPayrollYear())
                .status(payrollRun.getStatus())
                .totalGross(payrollRun.getTotalGross())
                .totalNet(payrollRun.getTotalNet())
                .totalPaye(payrollRun.getTotalPaye())
                .totalPensionEmployee(payrollRun.getTotalPensionEmployee())
                .totalPensionEmployer(payrollRun.getTotalPensionEmployer())
                .totalNhf(payrollRun.getTotalNhf())
                .initiatedBy(PayrollRunResponse.UserDto.builder()
                        .id(payrollRun.getInitiatedBy().getId())
                        .email(payrollRun.getInitiatedBy().getEmail())
                        .role(payrollRun.getInitiatedBy().getRole().name())
                        .build())
                .approvedBy(payrollRun.getApprovedBy() != null ?
                        PayrollRunResponse.UserDto.builder()
                                .id(payrollRun.getApprovedBy().getId())
                                .email(payrollRun.getApprovedBy().getEmail())
                                .role(payrollRun.getApprovedBy().getRole().name())
                                .build() : null)
                .approvedAt(approvedAt)
                .rejectionReason(payrollRun.getRejectionReason())
                .entries(entryResponses)
                .period(payrollRun.getPeriod())
                .editable(payrollRun.isEditable())
                .build();
    }

    @Transactional(readOnly = true)
    public List<PayrollRunResponse.PayrollEntryResponse> getPayrollEntryResponses(UUID payrollRunId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId);
        return entries.stream().map(this::toEntryResponse).toList();
    }

    private PayrollRunResponse.PayrollEntryResponse toEntryResponse(PayrollEntry entry) {
        return PayrollRunResponse.PayrollEntryResponse.builder()
                .id(entry.getId())
                .basicSalary(entry.getBasicSalary())
                .housingAllowance(entry.getHousingAllowance())
                .transportAllowance(entry.getTransportAllowance())
                .otherAllowances(entry.getOtherAllowances())
                .grossSalary(entry.getGrossSalary())
                .pensionEmployee(entry.getPensionEmployee())
                .pensionEmployer(entry.getPensionEmployer())
                .nhfDeduction(entry.getNhfDeduction())
                .payeTax(entry.getPayeTax())
                .netSalary(entry.getNetSalary())
                .employeeName(entry.getEmployee().getFullName())
                .employeeNumber(entry.getEmployee().getEmployeeNumber())
                .build();
    }
}
