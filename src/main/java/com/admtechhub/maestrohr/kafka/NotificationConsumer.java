package com.admtechhub.maestrohr.kafka;

import com.admtechhub.maestrohr.notification.EmailService;
import com.admtechhub.maestrohr.notification.TermiiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    private final Optional<EmailService> emailService;
    private final TermiiClient termiiClient;

    @KafkaListener(topics = "maestrohr.notifications.send", groupId = "${spring.kafka.consumer.group-id}")
    public void processNotification(NotificationEvent event) {
        try {
            switch (event.getType()) {
                case "EMAIL" -> emailService.ifPresentOrElse(
                        svc -> {
                            if (event.getAttachmentBytes() != null) {
                                svc.sendTemplatedEmailWithAttachment(
                                        event.getTo(), event.getSubject(),
                                        event.getTemplateName(), event.getVariables(),
                                        event.getAttachmentBytes(), event.getAttachmentName());
                            } else {
                                svc.sendTemplatedEmail(
                                        event.getTo(), event.getSubject(),
                                        event.getTemplateName(), event.getVariables());
                            }
                        },
                        () -> log.warn("Email service not configured; skipping notification to {}", event.getTo())
                );
                case "SMS" -> termiiClient.sendSms(event.getTo(), event.getSmsMessage());
                default -> log.warn("Unknown notification type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Failed to process {} notification to {}: {}",
                    event.getType(), event.getTo(), e.getMessage());
        }
    }
}
