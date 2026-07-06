package com.admtechhub.maestrohr.kafka;

import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fix A: PayrollNotificationConsumer must acknowledge the Kafka offset in a finally block -
 * previously the manual-ack container mode was configured but the consumer never called
 * ack.acknowledge() at all, so no offset ever committed and the topic replayed from the
 * beginning on every restart/rebalance (AuditConsumer, in the same package, already did this
 * correctly). Verifies the offset commits both on success AND when processing throws.
 */
@ExtendWith(MockitoExtension.class)
class PayrollNotificationConsumerTest {

    @Mock PayrollEntryRepository payrollEntryRepository;
    @Mock NotificationService notificationService;
    @Mock Acknowledgment acknowledgment;

    @InjectMocks PayrollNotificationConsumer consumer;

    @Test
    void processPayrollApproved_success_acknowledgesOffset() {
        PayrollApprovedEvent event = new PayrollApprovedEvent(UUID.randomUUID(), UUID.randomUUID());
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(event.getPayrollRunId()))
                .thenReturn(List.of());

        consumer.processPayrollApproved(event, acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void processPayrollApproved_processingThrows_stillAcknowledgesOffset() {
        PayrollApprovedEvent event = new PayrollApprovedEvent(UUID.randomUUID(), UUID.randomUUID());
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(event.getPayrollRunId()))
                .thenThrow(new RuntimeException("DB unavailable"));

        // The consumer catches and logs internally rather than rethrowing, but the offset
        // must still commit via the finally block regardless of how processing went.
        consumer.processPayrollApproved(event, acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void processPayrollApproved_entryNotificationThrows_stillAcknowledgesOffset() {
        PayrollApprovedEvent event = new PayrollApprovedEvent(UUID.randomUUID(), UUID.randomUUID());
        PayrollEntry entry = org.mockito.Mockito.mock(PayrollEntry.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(payrollEntryRepository.findByPayrollRunIdWithEntities(event.getPayrollRunId()))
                .thenReturn(List.of(entry));
        org.mockito.Mockito.doThrow(new RuntimeException("notification failed"))
                .when(notificationService).sendPayslipNotification(any(), any(), any());

        consumer.processPayrollApproved(event, acknowledgment);

        verify(acknowledgment).acknowledge();
    }
}
