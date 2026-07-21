package com.admtechhub.maestrohr.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditEventProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks AuditEventProducer producer;

    private static AuditEvent event() {
        AuditEvent e = new AuditEvent();
        e.setHttpMethod("GET");
        e.setRequestPath("/api/thing");
        return e;
    }

    @Test
    void publish_kafkaEnabled_sendsAndReturnsTrue() {
        assertTrue(producer.publish(event()));
        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    void publish_kafkaDisabled_returnsFalseWithoutTouchingKafka() {
        ReflectionTestUtils.setField(producer, "kafkaEnabled", false);

        assertFalse(producer.publish(event()),
                "disabled Kafka must return false so the caller writes the audit row directly");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }
}
