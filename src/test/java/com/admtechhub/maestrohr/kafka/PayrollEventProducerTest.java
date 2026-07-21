package com.admtechhub.maestrohr.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fix A: publishPayrollApproved used to fire-and-forget kafkaTemplate.send(...), discarding
 * the returned future entirely - a broker-side failure (or a hung send blocking past
 * max.block.ms) was silently invisible to the caller, and PayrollRunService's Kafka-unavailable
 * sync fallback never triggered. Now waits on the future (bounded by .get(2, SECONDS)) and
 * throws on failure/timeout so the caller's existing catch(Exception) fallback actually fires.
 */
@ExtendWith(MockitoExtension.class)
class PayrollEventProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks PayrollEventProducer producer;

    @Test
    void publishPayrollApproved_brokerAcks_completesNormally() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        producer.publishPayrollApproved(UUID.randomUUID(), UUID.randomUUID());
        // No exception thrown = success; nothing further to assert.
    }

    @Test
    void publishPayrollApproved_sendFails_throwsInsteadOfSilentlyVanishing() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        assertThrows(IllegalStateException.class,
                () -> producer.publishPayrollApproved(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void publishPayrollApproved_sendNeverCompletes_timesOutAndThrows() {
        // A future that never completes simulates a send() blocked past max.block.ms -
        // the bounded .get(2, SECONDS) must still return (by throwing), not hang forever.
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        assertThrows(IllegalStateException.class,
                () -> producer.publishPayrollApproved(UUID.randomUUID(), UUID.randomUUID()),
                "a send() that never completes must time out rather than block indefinitely");
    }

    @Test
    void publishPayrollApproved_kafkaDisabled_throwsWithoutTouchingKafka() {
        ReflectionTestUtils.setField(producer, "kafkaEnabled", false);

        assertThrows(IllegalStateException.class,
                () -> producer.publishPayrollApproved(UUID.randomUUID(), UUID.randomUUID()));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }
}
