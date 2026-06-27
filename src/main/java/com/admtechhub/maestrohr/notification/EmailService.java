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
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "spring.mail.host", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    public void sendEmailWithAttachment(String to, String subject, String body,
                                        byte[] attachment, String attachmentName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);

            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, true);

            if (attachment != null && attachment.length > 0) {
                helper.addAttachment(attachmentName, new ByteArrayResource(attachment));
            }

            mailSender.send(message);
            log.info("Email sent to: {}", to);

        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    public void sendSimpleEmail(String to, String subject, String body) {
        sendEmailWithAttachment(to, subject, body, null, null);
    }

    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        sendEmailWithAttachment(to, subject, htmlBody, null, null);
    }

    /**
     * Render a Thymeleaf template under {@code templates/email/} with the supplied variables and
     * send it as an HTML email. {@code templateName} is the path under that folder without the
     * {@code .html} suffix, e.g. {@code "email/welcome-employee"}.
     */
    public void sendTemplatedEmail(String to, String subject, String templateName, Map<String, Object> vars) {
        Context context = new Context();
        if (vars != null) {
            context.setVariables(vars);
        }
        String html = templateEngine.process(templateName, context);
        sendEmailWithAttachment(to, subject, html, null, null);
    }

    /** Same as {@link #sendTemplatedEmail} but with a PDF (or other) attachment. */
    public void sendTemplatedEmailWithAttachment(String to, String subject, String templateName,
                                                 Map<String, Object> vars,
                                                 byte[] attachment, String attachmentName) {
        Context context = new Context();
        if (vars != null) {
            context.setVariables(vars);
        }
        String html = templateEngine.process(templateName, context);
        sendEmailWithAttachment(to, subject, html, attachment, attachmentName);
    }
}
