package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.kafka.PayrollEventProducer;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.event.PayrollApprovedAppEvent;
import com.admtechhub.maestrohr.payroll.event.PayrollMarkedPaidAppEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Handles side effects that must execute AFTER a payroll run's approval / mark-as-paid
 * transaction commits — Kafka publish, payslip/notification dispatch, in-app notifications.
 * Runs asynchronously so the originating HTTP response returns immediately. Mirrors
 * {@link com.admtechhub.maestrohr.employee.EmployeePostCommitProcessor}'s exact pattern.
 *
 * <p>Before this fix, {@code PayrollRunService.approvePayroll}/{@code markAsPaid} ran these same
 * side effects INSIDE the approval/completion transaction: a rollback after that point (or a
 * race with the async consumer reading pre-commit state) could dispatch payslips or salary
 * notifications for a payroll run that was never actually approved/completed. Tenant context is
 * automatically propagated to the async thread by {@code TenantContextTaskDecorator}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollPostCommitProcessor {

    private final PayrollEntryRepository payrollEntryRepository;
    private final NotificationService notificationService;
    private final PayrollEventProducer payrollEventProducer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePayrollApproved(PayrollApprovedAppEvent event) {
        log.info("Processing post-commit side effects for approved payroll run {}", event.payrollRunId());

        // JOIN FETCH loads employee + payrollRun eagerly so they remain accessible after the
        // repository's own transaction closes - same pattern PayrollNotificationConsumer uses.
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunIdWithEntities(event.payrollRunId());

        try {
            payrollEventProducer.publishPayrollApproved(event.payrollRunId(), event.tenantId());
        } catch (Exception e) {
            log.warn("Kafka unavailable; sending payslip notifications synchronously: {}", e.getMessage());
            for (PayrollEntry entry : entries) {
                notificationService.sendPayslipNotification(entry, entry.getEmployee(), event.period());
            }
        }

        notificationService.createInAppNotification(
                event.approvedByEmail(),
                "PAYROLL_APPROVED",
                "Payroll approved",
                "Payroll run " + event.period() + " has been approved.",
                "/payroll/" + event.payrollRunId()
        );

        if (event.initiatedByEmail() != null && !event.initiatedByEmail().equalsIgnoreCase(event.approvedByEmail())) {
            notificationService.createInAppNotification(
                    event.initiatedByEmail(),
                    "PAYROLL_APPROVED",
                    "Payroll approved",
                    "Payroll run " + event.period() + " was approved by " + event.approvedByEmail() + ".",
                    "/payroll/" + event.payrollRunId()
            );
        }

        log.info("Post-commit processing complete for payroll run {}", event.payrollRunId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePayrollMarkedPaid(PayrollMarkedPaidAppEvent event) {
        log.info("Processing post-commit side effects for marked-paid payroll run {}", event.payrollRunId());

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunIdWithEntities(event.payrollRunId());
        for (PayrollEntry entry : entries) {
            notificationService.sendSalaryProcessedNotification(
                    entry, entry.getEmployee(), event.period(), event.companyName());
        }

        if (event.initiatedByEmail() != null) {
            notificationService.createInAppNotification(
                    event.initiatedByEmail(),
                    "PAYROLL_COMPLETED",
                    "Payroll completed",
                    "Payroll run " + event.period() + " has been marked as paid.",
                    "/payroll/" + event.payrollRunId()
            );
        }

        log.info("Post-commit processing complete for marked-paid payroll run {}, {} employees notified",
                event.payrollRunId(), entries.size());
    }
}
