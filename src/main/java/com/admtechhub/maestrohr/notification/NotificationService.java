package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final PayslipGenerator payslipGenerator;
    private final TermiiClient termiiClient;
    private final InAppNotificationRepository inAppNotificationRepository;
    private final Optional<EmailService> emailService;

    @Async
    public void sendPayslipNotification(PayrollEntry entry, Employee employee, String period) {
        log.info("Generating payslip for employee: {}", employee.getEmployeeNumber());

        byte[] payslipPdf = payslipGenerator.generatePayslip(entry, employee, period);

        if (payslipPdf != null) {
            // Send email if email service is available
            if (emailService.isPresent()) {
                String subject = "Payslip for " + period;
                String body = String.format(
                        "<h3>Dear %s,</h3>" +
                                "<p>Your payslip for %s is attached to this email.</p>" +
                                "<p><strong>Net Salary:</strong> ₦%.2f</p>" +
                                "<p>Thank you.</p>",
                        employee.getFullName(), period, entry.getNetSalary() / 100.0
                );

                emailService.get().sendEmailWithAttachment(
                        employee.getEmail(), subject, body, payslipPdf,
                        "payslip_" + period + ".pdf"
                );
            } else {
                log.warn("Email service not available. Skipping email for: {}", employee.getEmail());
            }

            // Always send SMS summary
            String smsMessage = String.format(
                    "MaestroHR: Your salary for %s is ₦%.2f. Check your email for payslip.",
                    period, entry.getNetSalary() / 100.0
            );
            termiiClient.sendSms(employee.getPhone(), smsMessage);
            createInAppNotification(
                    employee.getEmail(),
                    "PAYSLIP_READY",
                    "Payslip ready",
                    String.format("Your payslip for %s is ready. Net salary: ₦%.2f.", period, entry.getNetSalary() / 100.0),
                    "/reports/payslip?employeeId=" + employee.getId() + "&payrollRunId=" + entry.getPayrollRun().getId()
            );

            log.info("Payslip notification sent to: {}", employee.getEmail());
        }
    }

    public void createInAppNotification(String recipientEmail, String type, String title, String message, String link) {
        UUID tenantId = null;
        String tenant = TenantContext.getCurrentTenant();
        if (tenant != null && !tenant.isBlank()) {
            try {
                tenantId = UUID.fromString(tenant);
            } catch (IllegalArgumentException ignored) {
            }
        }

        inAppNotificationRepository.save(InAppNotification.builder()
                .tenantId(tenantId)
                .recipientEmail(recipientEmail)
                .type(type)
                .title(title)
                .message(message)
                .link(link)
                .build());
    }

    public List<InAppNotification> getMyNotifications(String recipientEmail) {
        return inAppNotificationRepository.findTop20ByRecipientEmailOrderByCreatedAtDesc(recipientEmail);
    }

    public long getUnreadCount(String recipientEmail) {
        return inAppNotificationRepository.countByRecipientEmailAndIsReadFalse(recipientEmail);
    }

    public void markAsRead(UUID id, String recipientEmail) {
        inAppNotificationRepository.markAsRead(id, recipientEmail);
    }

    public void markAllAsRead(String recipientEmail) {
        inAppNotificationRepository.markAllAsRead(recipientEmail);
    }

    @Async
    public void sendWelcomeNotification(Employee employee, String password) {
        log.info("Sending welcome notification to employee: {}", employee.getEmail());

        String welcomeMessage = String.format(
                "Welcome to MaestroHR! Your account has been created.\n\n" +
                        "Login Email: %s\n" +
                        "Temporary Password: %s\n\n" +
                        "Please login at: http://localhost:8080/login\n\n" +
                        "You will be prompted to change your password on first login.",
                employee.getEmail(), password
        );

        // Send SMS if phone number exists
        if (employee.getPhone() != null && !employee.getPhone().isEmpty()) {
            String smsMessage = String.format(
                    "MaestroHR: Your account has been created. Login with Email: %s, Password: %s",
                    employee.getEmail(), password
            );
            termiiClient.sendSms(employee.getPhone(), smsMessage);
        }

        // Send email if email service is available
        if (emailService.isPresent()) {
            String subject = "Welcome to MaestroHR - Your Account Has Been Created";
            String htmlBody = String.format(
                    "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;'>" +
                            "<div style='background: #2563eb; padding: 20px; text-align: center;'>" +
                            "<h1 style='color: white; margin: 0;'>Welcome to MaestroHR</h1>" +
                            "</div>" +
                            "<div style='padding: 20px; border: 1px solid #e2e8f0;'>" +
                            "<p>Dear %s,</p>" +
                            "<p>Your account has been created in the MaestroHR system.</p>" +
                            "<div style='background: #f1f5f9; padding: 15px; border-radius: 8px; margin: 20px 0;'>" +
                            "<p><strong>Login Credentials:</strong></p>" +
                            "<p><strong>Email:</strong> %s</p>" +
                            "<p><strong>Temporary Password:</strong> %s</p>" +
                            "</div>" +
                            "<p><a href='http://localhost:8080/login' style='background: #2563eb; color: white; padding: 10px 20px; text-decoration: none; border-radius: 6px;'>Login to Your Account</a></p>" +
                            "<p>For security reasons, please change your password after your first login.</p>" +
                            "<p>Best regards,<br>MaestroHR Team</p>" +
                            "</div>" +
                            "</div>",
                    employee.getFullName(), employee.getEmail(), password
            );
            emailService.get().sendSimpleEmail(employee.getEmail(), subject, htmlBody);
        }

        // Create in-app notification
        createInAppNotification(
                employee.getEmail(),
                "WELCOME",
                "Welcome to MaestroHR!",
                String.format("Your account has been created. Welcome %s!", employee.getFirstName()),
                "/dashboard"
        );

        log.info("Welcome notification sent to employee: {}", employee.getEmail());
    }
}