package com.admtechhub.maestrohr.kafka;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollNotificationConsumer {

    private final PayrollEntryRepository payrollEntryRepository;
    private final NotificationService notificationService;

    // Manual-ack: offset is committed only after this method returns (success or handled
    // failure), so a mid-processing crash replays the event instead of silently losing it.
    // Global spring.kafka.listener.ack-mode: manual applies to this (default) container factory.
    @KafkaListener(topics = "maestrohr.payroll.approved", groupId = "${spring.kafka.consumer.group-id}")
    public void processPayrollApproved(PayrollApprovedEvent event, Acknowledgment ack) {
        log.info("Processing PayrollApprovedEvent for run {}", event.getPayrollRunId());
        TenantContext.setCurrentTenant(event.getTenantId().toString());
        try {
            // JOIN FETCH loads employee + payrollRun eagerly so they remain accessible
            // after the repository's read-only transaction closes.
            List<PayrollEntry> entries =
                    payrollEntryRepository.findByPayrollRunIdWithEntities(event.getPayrollRunId());
            for (PayrollEntry entry : entries) {
                notificationService.sendPayslipNotification(
                        entry,
                        entry.getEmployee(),
                        entry.getPayrollRun().getPeriod()
                );
            }
            log.info("Queued payslip notifications for {} entries in run {}",
                    entries.size(), event.getPayrollRunId());
        } catch (Exception e) {
            log.error("Failed to process PayrollApprovedEvent for run {}: {}",
                    event.getPayrollRunId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
            ack.acknowledge();
        }
    }
}
