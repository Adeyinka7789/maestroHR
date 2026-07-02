package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.payroll.dto.PayrollRunResponse;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import com.admtechhub.maestrohr.kafka.PayrollEventProducer;
import com.admtechhub.maestrohr.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

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
    private final PayrollEventProducer payrollEventProducer;
    private final LeaveService leaveService;
    private final AttendanceService attendanceService;
    private final LoanService loanService;

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
     * Compute payroll for a run (generate entries).
     *
     * STEP 8 OPTIMIZATION: Prefetches leave, attendance, and loan data in batch
     * before the employee loop to eliminate N+1 service calls.
     *
     * Before: 1 + 4N queries (N = employee count)
     * After:  1 + 4 queries (constant, independent of employee count)
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

        // Delete existing entries for this payroll run to avoid duplicates
        List<PayrollEntry> existingEntries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        if (!existingEntries.isEmpty()) {
            log.info("Deleting {} existing entries for payroll run {}", existingEntries.size(), payrollRunId);
            payrollEntryRepository.deleteAll(existingEntries);
        }

        int workingDays = getWorkingDays(payrollRun.getPayrollMonth(), payrollRun.getPayrollYear());

        LocalDate periodStart = LocalDate.of(payrollRun.getPayrollYear(), payrollRun.getPayrollMonth(), 1);
        LocalDate periodEnd   = periodStart.plusMonths(1).minusDays(1);

        // Include employees terminated mid-period so they receive a prorated final pay.
        List<Employee> payrollEmployees = employeeRepository.findActiveOrTerminatedDuringPeriod(
                EmployeeStatus.ACTIVE, EmployeeStatus.TERMINATED, periodStart, periodEnd);

        if (payrollEmployees.isEmpty()) {
            throw new IllegalStateException("No active employees found for payroll");
        }

        // ================================================================
        // STEP 8: BATCH PREFETCH - Replace N+1 per-employee calls with 3 batch calls
        // ================================================================
        List<UUID> employeeIds = payrollEmployees.stream()
                .map(Employee::getId)
                .toList();

        log.info("Batch prefetching leave, attendance, and loan data for {} employees", employeeIds.size());

        // 1 batch call instead of N
        Map<UUID, Integer> unpaidLeaveDaysMap = leaveService.getUnpaidLeaveDaysBatch(
                employeeIds, periodStart, periodEnd);

        // 1 batch call instead of N
        Map<UUID, Integer> absentDaysMap = attendanceService.getAbsentDaysBatch(
                employeeIds, periodStart, periodEnd);

        // 1 batch call instead of N
        Map<UUID, Integer> lateDaysMap = attendanceService.getLateDaysBatch(
                employeeIds, periodStart, periodEnd);

        // 1 batch call instead of N
        Map<UUID, Long> loanDeductionMap = loanService.computeLoanDeductionsBatch(employeeIds);

        log.info("Batch prefetch complete. Building payroll entries...");
        // ================================================================

        List<PayrollEntry> entries = new ArrayList<>();
        long totalGross = 0;
        long totalNet = 0;
        long totalPaye = 0;
        long totalPensionEmployee = 0;
        long totalPensionEmployer = 0;
        long totalNhf = 0;

        for (Employee employee : payrollEmployees) {
            // Prorate for mid-month joiners and mid-month leavers
            LocalDate effectiveStart = employee.getEmploymentStartDate().isAfter(periodStart)
                    ? employee.getEmploymentStartDate() : periodStart;
            LocalDate effectiveEnd = (employee.getTerminationDate() != null
                    && employee.getTerminationDate().isBefore(periodEnd))
                    ? employee.getTerminationDate() : periodEnd;
            int daysWorked = effectiveEnd.isBefore(effectiveStart)
                    ? 0 : countWorkingDays(effectiveStart, effectiveEnd);

            // Use batch-prefetched values instead of per-employee service calls
            int unpaidLeaveDays = unpaidLeaveDaysMap.getOrDefault(employee.getId(), 0);
            int absentDays      = absentDaysMap.getOrDefault(employee.getId(), 0);
            int lateDays        = lateDaysMap.getOrDefault(employee.getId(), 0);
            long loanDeduction  = loanDeductionMap.getOrDefault(employee.getId(), 0L);

            PayrollEngine.PayrollResult result = payrollEngine.calculateEmployeePayroll(
                    employee, daysWorked, workingDays, unpaidLeaveDays, absentDays, loanDeduction);

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
                    .unpaidLeaveDeduction(result.getUnpaidLeaveDeduction())
                    .attendanceDeduction(result.getAttendanceDeduction())
                    .loanDeduction(result.getLoanDeduction())
                    .loanDeductionCapped(result.isLoanDeductionCapped())
                    .lateDaysInPeriod(lateDays)
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

        // Save new entries
        payrollEntryRepository.saveAll(entries);

        // Update payroll run totals
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
     * Get count of pending approval payroll runs
     */
    @Transactional(readOnly = true)
    public long getPendingApprovalsCount() {
        UUID tenantId = currentTenantId();
        return payrollRunRepository.countByTenantIdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL);
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

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
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

        // Guard: the loan deductions locked onto the entries at compute must still match the
        // current loan state, or the payslip total and the ledger about to be written would
        // disagree (a loan was paused / cancelled / added between compute and approval). On a
        // mismatch this throws → the run stays PENDING_APPROVAL and finance is told to reject &
        // recompute. Checked before any state change so nothing is half-applied.
        loanService.verifyDeductionsCurrent(payrollRun.getEntries());

        payrollRun.setStatus(PayrollStatus.APPROVED);

        // Phase 2 (apply): decrement loan balances and write the repayment ledger exactly once.
        // Runs inside this approval transaction, so a failure rolls the approval back too. The
        // ledger's UNIQUE(loan_id, payroll_run_id) plus canApprove() (PENDING_APPROVAL → APPROVED
        // happens once) make a double-apply impossible.
        loanService.applyRepaymentsForRun(payrollRun, payrollRun.getEntries());

        payrollRun.setApprovedBy(approvedBy);
        payrollRun.setApprovedAt(LocalDateTime.now());

        PayrollRun updated = payrollRunRepository.save(payrollRun);

        // Publish event so PayrollNotificationConsumer generates + dispatches payslips async.
        // Falls back to the synchronous per-entry loop when Kafka is unavailable.
        try {
            payrollEventProducer.publishPayrollApproved(payrollRunId, currentTenantId());
        } catch (Exception e) {
            log.warn("Kafka unavailable; sending payslip notifications synchronously: {}", e.getMessage());
            for (PayrollEntry entry : payrollRun.getEntries()) {
                notificationService.sendPayslipNotification(
                        entry, entry.getEmployee(), payrollRun.getPeriod());
            }
        }
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
     * Mark an APPROVED run as paid (APPROVED → COMPLETED). This is a manual, human-attested
     * step: the payment file was downloaded and uploaded to the bank out-of-band, so finance
     * confirms completion by hand — MaestroHR never moves funds and can't observe the payment
     * itself. A non-APPROVED run raises {@link IllegalStateException}.
     */
    @Transactional
    public PayrollRunResponse markAsPaid(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (!payrollRun.canComplete()) {
            throw new IllegalStateException(
                    "Payroll must be APPROVED or DISBURSING to mark as paid. Current status: " + payrollRun.getStatus());
        }

        String period = payrollRun.getPeriod();
        String companyName = payrollRun.getTenant().getCompanyName();

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.PAID);
        }
        payrollEntryRepository.saveAll(entries);

        for (PayrollEntry entry : entries) {
            notificationService.sendSalaryProcessedNotification(
                    entry, entry.getEmployee(), period, companyName);
        }

        payrollRun.setStatus(PayrollStatus.COMPLETED);
        PayrollRun updated = payrollRunRepository.save(payrollRun);

        if (payrollRun.getInitiatedBy() != null) {
            notificationService.createInAppNotification(
                    payrollRun.getInitiatedBy().getEmail(),
                    "PAYROLL_COMPLETED",
                    "Payroll completed",
                    "Payroll run " + period + " has been marked as paid.",
                    "/payroll/" + payrollRunId
            );
        }
        log.info("Payroll run {} marked as paid (COMPLETED), {} employees notified", payrollRunId, entries.size());

        return toResponse(updated);
    }

    /**
     * Reverse an APPROVED, DISBURSING, or COMPLETED payroll run. This is a system-level
     * correction — it rolls back the loan ledger written at approval, marks every entry's
     * transfer status as REVERSED, and transitions the run to REVERSED. It does NOT attempt
     * to claw back money already disbursed via Paystack or the bank; that must be handled
     * operationally outside the system.
     *
     * <p>The method is idempotent against the loan ledger (each LoanRepayment row is only
     * reversed once) but a second call on an already-REVERSED run is rejected immediately.
     */
    @Transactional
    public PayrollReverseResult reversePayrollRun(UUID runId, String reversedByEmail, String reason) {
        PayrollRun payrollRun = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + runId));

        if (payrollRun.getStatus() == PayrollStatus.REVERSED) {
            throw new IllegalStateException("This payroll run has already been reversed.");
        }
        if (!payrollRun.canReverse()) {
            throw new IllegalStateException(
                    "Only APPROVED, DISBURSING, or COMPLETED runs can be reversed. Current status: "
                            + payrollRun.getStatus());
        }
        if (reason == null || reason.strip().length() < 10) {
            throw new IllegalArgumentException("Reversal reason must be at least 10 characters.");
        }

        User reversedBy = userRepository.findByEmail(reversedByEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + reversedByEmail));

        boolean wasDisbursed = payrollRun.getStatus() == PayrollStatus.DISBURSING
                || payrollRun.getStatus() == PayrollStatus.COMPLETED;

        // Roll back loan ledger (idempotent per repayment row)
        loanService.reverseRepaymentsForRun(payrollRun);

        // Mark every entry reversed
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(runId, currentTenantId());
        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.REVERSED);
        }
        payrollEntryRepository.saveAll(entries);

        payrollRun.setStatus(PayrollStatus.REVERSED);
        payrollRun.setReversedBy(reversedBy);
        payrollRun.setReversedAt(LocalDateTime.now());
        payrollRun.setReversalReason(reason.strip());
        payrollRunRepository.save(payrollRun);

        // Notify initiator and approver
        String period = payrollRun.getPeriod();
        String msg = "Payroll run " + period + " has been reversed. Reason: " + reason.strip();
        if (payrollRun.getInitiatedBy() != null) {
            notificationService.createInAppNotification(
                    payrollRun.getInitiatedBy().getEmail(),
                    "PAYROLL_REVERSED", "Payroll reversed", msg, "/payroll/" + runId);
        }
        if (payrollRun.getApprovedBy() != null
                && !payrollRun.getApprovedBy().getEmail().equalsIgnoreCase(
                payrollRun.getInitiatedBy() != null ? payrollRun.getInitiatedBy().getEmail() : "")) {
            notificationService.createInAppNotification(
                    payrollRun.getApprovedBy().getEmail(),
                    "PAYROLL_REVERSED", "Payroll reversed", msg, "/payroll/" + runId);
        }

        log.info("Payroll run {} reversed by {} — wasDisbursed={}", runId, reversedByEmail, wasDisbursed);

        String warning = wasDisbursed
                ? "Note: This payroll was already disbursed. You must manually recover funds from employees. "
                + "This reversal only corrects system records."
                : null;

        return new PayrollReverseResult(toResponse(payrollRun), warning);
    }

    /** Return type for reversePayrollRun, carrying the updated run and an optional disbursement warning. */
    public record PayrollReverseResult(PayrollRunResponse run, String warning) {}

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
        return payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
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
        return countWorkingDays(firstDay, lastDay);
    }

    private int countWorkingDays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() != 7) {
                count++;
            }
        }
        return count;
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
                        .unpaidLeaveDeduction(entry.getUnpaidLeaveDeduction())
                        .attendanceDeduction(entry.getAttendanceDeduction())
                        .loanDeduction(entry.getLoanDeduction())
                        .lateDaysInPeriod(entry.getLateDaysInPeriod())
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
                .reversedBy(payrollRun.getReversedBy() != null ?
                        PayrollRunResponse.UserDto.builder()
                                .id(payrollRun.getReversedBy().getId())
                                .email(payrollRun.getReversedBy().getEmail())
                                .role(payrollRun.getReversedBy().getRole().name())
                                .build() : null)
                .reversedAt(payrollRun.getReversedAt())
                .reversalReason(payrollRun.getReversalReason())
                .entries(entryResponses)
                .period(payrollRun.getPeriod())
                .editable(payrollRun.isEditable())
                .build();
    }

    @Transactional(readOnly = true)
    public List<PayrollRunResponse.PayrollEntryResponse> getPayrollEntryResponses(UUID payrollRunId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
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
                .unpaidLeaveDeduction(entry.getUnpaidLeaveDeduction())
                .attendanceDeduction(entry.getAttendanceDeduction())
                .lateDaysInPeriod(entry.getLateDaysInPeriod())
                .netSalary(entry.getNetSalary())
                .employeeName(entry.getEmployee().getFullName())
                .employeeNumber(entry.getEmployee().getEmployeeNumber())
                .build();
    }

    @Transactional(readOnly = true)
    public List<PayrollRunResponse> getPendingApprovals() {
        UUID tenantId = currentTenantId();
        List<PayrollRun> payrollRuns = payrollRunRepository.findByTenant_IdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL);
        return payrollRuns.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportPayrollToExcel(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());

        // Defensive: ensure entries is never null
        if (entries == null) {
            entries = Collections.emptyList();
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Payroll - " + payrollRun.getPeriod());

            // Header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Header row
            Row header = sheet.createRow(0);
            String[] columns = {"Employee Number", "Employee Name", "Basic Salary", "Housing Allowance",
                    "Transport Allowance", "Other Allowances", "Gross Salary", "PAYE Tax", "Pension (Employee)",
                    "Pension (Employer)", "NHF", "Net Salary"};
            for (int i = 0; i < columns.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            // Data rows with null-safe handling
            int rowNum = 1;
            for (PayrollEntry entry : entries) {
                Row row = sheet.createRow(rowNum++);

                // Handle missing employee gracefully
                Employee employee = entry.getEmployee();
                if (employee == null) {
                    row.createCell(0).setCellValue("DELETED");
                    row.createCell(1).setCellValue("Deleted employee (entry ID: " + entry.getId() + ")");
                } else {
                    // Employee exists – safe to access fields
                    row.createCell(0).setCellValue(employee.getEmployeeNumber() != null ? employee.getEmployeeNumber() : "");
                    row.createCell(1).setCellValue(employee.getFullName() != null ? employee.getFullName() : "");
                }

                // All monetary fields are handled via formatAmount (null safe)
                row.createCell(2).setCellValue(formatAmount(entry.getBasicSalary()));
                row.createCell(3).setCellValue(formatAmount(entry.getHousingAllowance()));
                row.createCell(4).setCellValue(formatAmount(entry.getTransportAllowance()));
                row.createCell(5).setCellValue(formatAmount(entry.getOtherAllowances()));
                row.createCell(6).setCellValue(formatAmount(entry.getGrossSalary()));
                row.createCell(7).setCellValue(formatAmount(entry.getPayeTax()));
                row.createCell(8).setCellValue(formatAmount(entry.getPensionEmployee()));
                row.createCell(9).setCellValue(formatAmount(entry.getPensionEmployer()));
                row.createCell(10).setCellValue(formatAmount(entry.getNhfDeduction()));
                row.createCell(11).setCellValue(formatAmount(entry.getNetSalary()));
            }

            // Auto-size columns
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            // Log the full root cause for debugging
            log.error("Failed to generate Excel export for payroll run: {}", payrollRunId, e);
            throw new RuntimeException("Failed to generate Excel export: " + e.getMessage(), e);
        }
    }

    private double formatAmount(Long amountInKobo) {
        if (amountInKobo == null) return 0.0;
        return amountInKobo / 100.0;
    }
}
