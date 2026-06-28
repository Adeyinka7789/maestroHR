package com.admtechhub.maestrohr.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes a PayrollApprovedEvent to kick off async payslip generation + dispatch.
     * Deliberately lets exceptions propagate so the caller can fall back to the
     * synchronous notification loop when Kafka is unavailable.
     */
    public void publishPayrollApproved(UUID payrollRunId, UUID tenantId) {
        kafkaTemplate.send("maestrohr.payroll.approved", payrollRunId.toString(),
                new PayrollApprovedEvent(payrollRunId, tenantId));
        log.info("Published PayrollApprovedEvent for run {}", payrollRunId);
    }
}
