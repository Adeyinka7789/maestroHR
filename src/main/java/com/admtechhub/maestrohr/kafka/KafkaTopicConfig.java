package com.admtechhub.maestrohr.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaTopicConfig {

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
}
