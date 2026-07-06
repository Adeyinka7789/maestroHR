package com.admtechhub.maestrohr.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes a PayrollApprovedEvent to kick off async payslip generation + dispatch.
     * Waits up to 2 seconds for broker acknowledgment (bounded by
     * spring.kafka.producer.properties.max.block.ms so a send never blocks indefinitely
     * on metadata) and deliberately throws on any failure/timeout so the caller can fall
     * back to the synchronous notification loop when Kafka is unavailable, instead of the
     * event silently vanishing on a fire-and-forget send.
     */
    public void publishPayrollApproved(UUID payrollRunId, UUID tenantId) {
        try {
            kafkaTemplate.send("maestrohr.payroll.approved", payrollRunId.toString(),
                            new PayrollApprovedEvent(payrollRunId, tenantId))
                    .get(2, TimeUnit.SECONDS);
            log.info("Published PayrollApprovedEvent for run {}", payrollRunId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing PayrollApprovedEvent for run " + payrollRunId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(
                    "Failed to publish PayrollApprovedEvent for run " + payrollRunId, e);
        }
    }
}
