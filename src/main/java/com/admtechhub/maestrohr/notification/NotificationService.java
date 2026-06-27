package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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

    /** Base URL for links embedded in outgoing emails (login, password reset, …). */
    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    @Async
    public void sendPayslipNotification(PayrollEntry entry, Employee employee, String period) {
        log.info("Generating payslip for employee: {}", employee.getEmployeeNumber());

        byte[] payslipPdf = payslipGenerator.generatePayslip(entry, employee, period);

        if (payslipPdf != null) {
            // Send email if email service is available
            if (emailService.isPresent()) {
                emailService.get().sendTemplatedEmailWithAttachment(
                        employee.getEmail(),
                        "Payslip for " + period,
                        "email/payslip-notification",
                        Map.of(
                                "firstName", safe(employee.getFirstName()),
                                "period", period,
                                "netSalary", String.format("%,.2f", entry.getNetSalary() / 100.0)
                        ),
                        payslipPdf,
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
            emailService.get().sendTemplatedEmail(
                    employee.getEmail(),
                    "Welcome to MaestroHR - Your Account Has Been Created",
                    "email/welcome-employee",
                    Map.of(
                            "firstName", safe(employee.getFirstName()),
                            "email", safe(employee.getEmail()),
                            "tempPassword", safe(password),
                            "loginUrl", appUrl + "/login"
                    )
            );
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

    /**
     * Forgot-password email. Runs with no tenant session (the user is locked out), so it only ever
     * sends email — no in-app notification (which would need a tenant context).
     */
    public void sendPasswordResetEmail(String toEmail, String firstName, String resetUrl, int expiryMinutes) {
        if (emailService.isEmpty()) {
            log.warn("Email service not available. Skipping password-reset email for: {}", toEmail);
            return;
        }
        emailService.get().sendTemplatedEmail(
                toEmail,
                "Reset your MaestroHR password",
                "email/password-reset",
                Map.of(
                        "firstName", safe(firstName),
                        "resetUrl", resetUrl,
                        "expiryMinutes", expiryMinutes
                )
        );
        log.info("Password-reset email sent to: {}", toEmail);
    }

    /** Leave-approved email (in-app + SMS are handled by the caller). */
    public void sendLeaveApprovedEmail(Employee employee, String leaveType, String startDate,
                                       String endDate, int days) {
        if (emailService.isEmpty()) {
            return;
        }
        emailService.get().sendTemplatedEmail(
                employee.getEmail(),
                "Your leave request has been approved",
                "email/leave-approved",
                Map.of(
                        "firstName", safe(employee.getFirstName()),
                        "leaveType", safe(leaveType),
                        "startDate", safe(startDate),
                        "endDate", safe(endDate),
                        "days", days
                )
        );
    }

    /** Leave-rejected email (in-app + SMS are handled by the caller). */
    public void sendLeaveRejectedEmail(Employee employee, String leaveType, String reason) {
        if (emailService.isEmpty()) {
            return;
        }
        emailService.get().sendTemplatedEmail(
                employee.getEmail(),
                "Update on your leave request",
                "email/leave-rejected",
                Map.of(
                        "firstName", safe(employee.getFirstName()),
                        "leaveType", safe(leaveType),
                        "reason", safe(reason)
                )
        );
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
