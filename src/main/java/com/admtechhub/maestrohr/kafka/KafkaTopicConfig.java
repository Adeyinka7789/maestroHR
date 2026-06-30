package com.admtechhub.maestrohr.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaTopicConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicConfig.class);

    @Bean
    public NewTopic payrollApprovedTopic() {
        return TopicBuilder.name("maestrohr.payroll.approved")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationsTopic() {
        return TopicBuilder.name("maestrohr.notifications.send")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name("maestrohr.audit.events")
                .partitions(3)
                .replicas(1)
                .build();
    }

    // Dedicated container factory for the audit consumer. AckMode.MANUAL_IMMEDIATE
    // means the consumer commits the offset only after AuditConsumer calls ack.acknowledge(),
    // which happens only after the DB write succeeds — so audit events are never silently dropped.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> auditKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("Kafka message failed after 3 retries. Topic: {}, Offset: {}, Error: {}",
                    record.topic(), record.offset(), exception.getMessage());
        }, backOff);
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateException.class
        );
        return handler;
    }
}