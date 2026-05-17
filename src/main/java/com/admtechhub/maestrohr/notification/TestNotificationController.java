package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/test-notifications")
@RequiredArgsConstructor
@Slf4j
public class TestNotificationController {

    private final Optional<EmailService> emailService;
    private final TermiiClient termiiClient;
    private final NotificationService notificationService;

    @GetMapping("/email")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> testEmail(@RequestParam String to, @RequestParam String subject) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, String> result = new HashMap<>();

        if (emailService.isEmpty()) {
            result.put("status", "skipped");
            result.put("message", "Email service is not configured. Please configure spring.mail properties in application.properties");
            return ResponseEntity.ok(ApiResponse.success("Email not configured", result));
        }

        try {
            String htmlBody = """
                <div style='font-family: Arial, sans-serif; padding: 20px;'>
                    <h2 style='color: #2563eb;'>MaestroHR Test Email</h2>
                    <p>This is a test email from MaestroHR system.</p>
                    <p>Sent by: <strong>%s</strong></p>
                    <p>Time: %s</p>
                    <hr>
                    <p style='color: #6b7280; font-size: 12px;'>This is an automated test message.</p>
                </div>
                """.formatted(email, java.time.LocalDateTime.now());

            emailService.get().sendHtmlEmail(to, subject + " (Test)", htmlBody);
            result.put("status", "success");
            result.put("message", "Email sent to " + to);
            log.info("Test email sent to {}", to);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            log.error("Test email failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success("Test email result", result));
    }

    @GetMapping("/sms")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> testSms(@RequestParam String phone, @RequestParam String message) {
        Map<String, String> result = new HashMap<>();

        try {
            termiiClient.sendSms(phone, "[TEST] " + message);
            result.put("status", "success");
            result.put("message", "SMS sent to " + phone);
            log.info("Test SMS sent to {}", phone);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            log.error("Test SMS failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success("Test SMS result", result));
    }

    @GetMapping("/in-app")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HR_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> testInAppNotification(@RequestParam String recipientEmail) {
        Map<String, String> result = new HashMap<>();

        try {
            notificationService.createInAppNotification(
                    recipientEmail,
                    "TEST_NOTIFICATION",
                    "Test Notification",
                    "This is a test in-app notification from MaestroHR.",
                    "/dashboard"
            );
            result.put("status", "success");
            result.put("message", "In-app notification sent to " + recipientEmail);
            log.info("Test in-app notification sent to {}", recipientEmail);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            log.error("Test in-app notification failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success("Test notification result", result));
    }
}