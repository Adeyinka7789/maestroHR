package com.admtechhub.maestrohr.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** When false (e.g. local dev), skip Kafka entirely and let the caller use its DB fallback. */
    @Value("${maestrohr.kafka.enabled:true}")
    private boolean kafkaEnabled = true;

    /**
     * Publishes an AuditEvent. Returns false on failure (or when Kafka is disabled) so
     * AuditTrailInterceptor can fall back to a direct synchronous DB write.
     */
    public boolean publish(AuditEvent event) {
        if (!kafkaEnabled) {
            return false;
        }
        try {
            kafkaTemplate.send("maestrohr.audit.events", event.getRequestPath(), event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish AuditEvent for {} {}: {}",
                    event.getHttpMethod(), event.getRequestPath(), e.getMessage());
            return false;
        }
    }
}
