package com.admtechhub.maestrohr.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service  // Uncomment this
@ConditionalOnProperty(name = "spring.mail.host", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendEmailWithAttachment(String to, String subject, String body,
                                        byte[] attachment, String attachmentName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, true);

            if (attachment != null) {
                helper.addAttachment(attachmentName, new ByteArrayResource(attachment));
            }

            mailSender.send(message);
            log.info("Email sent to: {}", to);

        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    // Simple email without attachment
    public void sendSimpleEmail(String to, String subject, String body) {
        sendEmailWithAttachment(to, subject, body, null, null);
    }
}