package com.admtechhub.maestrohr.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** When false (e.g. local dev), skip Kafka entirely and let the caller send synchronously. */
    @Value("${maestrohr.kafka.enabled:true}")
    private boolean kafkaEnabled = true;

    /**
     * Publishes a NotificationEvent. Returns false on failure (or when Kafka is disabled) so the
     * caller can fall back to a direct synchronous send.
     */
    public boolean publish(NotificationEvent event) {
        if (!kafkaEnabled) {
            return false;
        }
        try {
            kafkaTemplate.send("maestrohr.notifications.send", event.getTo(), event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish NotificationEvent to {}: {}", event.getTo(), e.getMessage());
            return false;
        }
    }
}
