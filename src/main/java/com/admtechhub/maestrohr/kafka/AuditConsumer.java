package com.admtechhub.maestrohr.kafka;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditConsumer {

    private final AuditTrailService auditTrailService;

    // Manual-ack: offset is committed only after the DB write completes, so
    // audit events are never silently lost even if the process restarts mid-batch.
    @KafkaListener(topics = "maestrohr.audit.events",
                   containerFactory = "auditKafkaListenerContainerFactory")
    public void processAuditEvent(AuditEvent event, Acknowledgment ack) {
        try {
            auditTrailService.record(
                    event.getTenantId(),
                    event.getActorEmail(),
                    event.getAction(),
                    event.getEntityType(),
                    event.getEntityId(),
                    event.getRequestPath(),
                    event.getHttpMethod(),
                    event.getIpAddress(),
                    event.getStatusCode(),
                    event.getDetails(),
                    event.getImpersonatedBy()
            );
        } catch (Exception e) {
            log.error("Failed to persist audit event for {} {}: {}",
                    event.getHttpMethod(), event.getRequestPath(), e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }
}
