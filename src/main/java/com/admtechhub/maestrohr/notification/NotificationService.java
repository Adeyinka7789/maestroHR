package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.kafka.NotificationEvent;
import com.admtechhub.maestrohr.kafka.NotificationProducer;
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
    private final NotificationProducer notificationProducer;

    /** Base URL for links embedded in outgoing emails (login, password reset, …). */
    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    @Async
    public void sendPayslipNotification(PayrollEntry entry, Employee employee, String period) {
        log.info("Generating payslip for employee: {}", employee.getEmployeeNumber());

        byte[] payslipPdf = payslipGenerator.generatePayslip(entry, employee, period);

        if (payslipPdf != null) {
            Map<String, Object> emailVars = Map.of(
                    "firstName", safe(employee.getFirstName()),
                    "period", period,
                    "netSalary", String.format("%,.2f", entry.getNetSalary() / 100.0)
            );
            NotificationEvent emailEvent = NotificationEvent.builder()
                    .type("EMAIL")
                    .to(employee.getEmail())
                    .subject("Payslip for " + period)
                    .templateName("email/payslip-notification")
                    .variables(emailVars)
                    .attachmentBytes(payslipPdf)
                    .attachmentName("payslip_" + period + ".pdf")
                    .build();
            if (!notificationProducer.publish(emailEvent)) {
                if (emailService.isPresent()) {
                    emailService.get().sendTemplatedEmailWithAttachment(
                            employee.getEmail(), "Payslip for " + period,
                            "email/payslip-notification", emailVars,
                            payslipPdf, "payslip_" + period + ".pdf");
                } else {
                    log.warn("Email service not available. Skipping payslip email for: {}", employee.getEmail());
                }
            }

            String smsMessage = String.format(
                    "MaestroHR: Your salary for %s is ₦%.2f. Check your email for payslip.",
                    period, entry.getNetSalary() / 100.0);
            NotificationEvent smsEvent = NotificationEvent.builder()
                    .type("SMS")
                    .to(employee.getPhone())
                    .smsMessage(smsMessage)
                    .build();
            if (!notificationProducer.publish(smsEvent)) {
                termiiClient.sendSms(employee.getPhone(), smsMessage);
            }

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

        if (employee.getPhone() != null && !employee.getPhone().isEmpty()) {
            String smsMessage = String.format(
                    "MaestroHR: Your account has been created. Login with Email: %s, Password: %s",
                    employee.getEmail(), password);
            NotificationEvent smsEvent = NotificationEvent.builder()
                    .type("SMS").to(employee.getPhone()).smsMessage(smsMessage).build();
            if (!notificationProducer.publish(smsEvent)) {
                termiiClient.sendSms(employee.getPhone(), smsMessage);
            }
        }

        Map<String, Object> emailVars = Map.of(
                "firstName", safe(employee.getFirstName()),
                "email", safe(employee.getEmail()),
                "tempPassword", safe(password),
                "loginUrl", appUrl + "/login"
        );
        NotificationEvent emailEvent = NotificationEvent.builder()
                .type("EMAIL")
                .to(employee.getEmail())
                .subject("Welcome to MaestroHR - Your Account Has Been Created")
                .templateName("email/welcome-employee")
                .variables(emailVars)
                .build();
        if (!notificationProducer.publish(emailEvent)) {
            emailService.ifPresent(svc -> svc.sendTemplatedEmail(
                    employee.getEmail(),
                    "Welcome to MaestroHR - Your Account Has Been Created",
                    "email/welcome-employee", emailVars));
        }

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
     * Invite-user email — sends login credentials to a newly created user account.
     * Runs within the inviting SYSTEM_ADMIN's request, so the tenant session is bound and
     * the in-app path is available, but we only send email here (no Employee record exists).
     */
    @Async
    public void sendUserInviteEmail(String toEmail, String tempPassword) {
        if (emailService.isEmpty()) {
            log.warn("Email service not available. Skipping invite email for: {}", toEmail);
            return;
        }
        emailService.get().sendTemplatedEmail(
                toEmail,
                "Your MaestroHR account has been created",
                "email/welcome-employee",
                Map.of(
                        "firstName", localPart(toEmail),
                        "email", safe(toEmail),
                        "tempPassword", safe(tempPassword),
                        "loginUrl", appUrl + "/login"
                )
        );
        log.info("User invite email sent to: {}", toEmail);
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

    /**
     * Notifies an employee that their salary was credited (Paystack transfer.success path).
     * Sends SMS + email (payslip-notification template) + in-app notification.
     */
    @Async
    public void sendSalaryCreditNotification(PayrollEntry entry, Employee employee, String period, String companyName) {
        String netFormatted = String.format("%,.2f", entry.getNetSalary() / 100.0);
        String smsMessage = String.format(
                "Dear %s, your salary of ₦%s for %s has been credited to your account. - %s",
                safe(employee.getFirstName()), netFormatted, period, safe(companyName));
        NotificationEvent smsEvent = NotificationEvent.builder()
                .type("SMS").to(employee.getPhone()).smsMessage(smsMessage).build();
        if (!notificationProducer.publish(smsEvent)) {
            termiiClient.sendSms(employee.getPhone(), smsMessage);
        }

        Map<String, Object> emailVars = Map.of(
                "firstName", safe(employee.getFirstName()),
                "period", period,
                "netSalary", netFormatted);
        NotificationEvent emailEvent = NotificationEvent.builder()
                .type("EMAIL").to(employee.getEmail())
                .subject("Salary Credited - " + period)
                .templateName("email/payslip-notification")
                .variables(emailVars)
                .build();
        if (!notificationProducer.publish(emailEvent)) {
            emailService.ifPresent(svc -> svc.sendTemplatedEmail(
                    employee.getEmail(), "Salary Credited - " + period,
                    "email/payslip-notification", emailVars));
        }

        createInAppNotification(employee.getEmail(), "SALARY_CREDITED", "Salary credited",
                String.format("Your salary of ₦%s for %s has been credited to your account.", netFormatted, period),
                "/reports/payslip?employeeId=" + employee.getId() + "&payrollRunId=" + entry.getPayrollRun().getId());
    }

    /**
     * Notifies an employee that their salary was processed (manual bank / markAsPaid path).
     * Sends SMS + in-app notification only (no PDF re-generation).
     */
    @Async
    public void sendSalaryProcessedNotification(PayrollEntry entry, Employee employee, String period, String companyName) {
        String netFormatted = String.format("%,.2f", entry.getNetSalary() / 100.0);
        String smsMessage = String.format(
                "Dear %s, your salary of ₦%s for %s has been processed. - %s",
                safe(employee.getFirstName()), netFormatted, period, safe(companyName));
        NotificationEvent smsEvent = NotificationEvent.builder()
                .type("SMS").to(employee.getPhone()).smsMessage(smsMessage).build();
        if (!notificationProducer.publish(smsEvent)) {
            termiiClient.sendSms(employee.getPhone(), smsMessage);
        }

        createInAppNotification(employee.getEmail(), "SALARY_PROCESSED", "Salary processed",
                String.format("Your salary of ₦%s for %s has been processed.", netFormatted, period),
                "/reports/payslip?employeeId=" + employee.getId() + "&payrollRunId=" + entry.getPayrollRun().getId());
    }

    /** Leave-approved email (in-app + SMS are handled by the caller). */
    public void sendLeaveApprovedEmail(Employee employee, String leaveType, String startDate,
                                       String endDate, int days) {
        Map<String, Object> vars = Map.of(
                "firstName", safe(employee.getFirstName()),
                "leaveType", safe(leaveType),
                "startDate", safe(startDate),
                "endDate", safe(endDate),
                "days", days
        );
        NotificationEvent event = NotificationEvent.builder()
                .type("EMAIL")
                .to(employee.getEmail())
                .subject("Your leave request has been approved")
                .templateName("email/leave-approved")
                .variables(vars)
                .build();
        if (!notificationProducer.publish(event)) {
            emailService.ifPresent(svc -> svc.sendTemplatedEmail(
                    employee.getEmail(), "Your leave request has been approved",
                    "email/leave-approved", vars));
        }
    }

    /** Leave-rejected email (in-app + SMS are handled by the caller). */
    public void sendLeaveRejectedEmail(Employee employee, String leaveType, String reason) {
        Map<String, Object> vars = Map.of(
                "firstName", safe(employee.getFirstName()),
                "leaveType", safe(leaveType),
                "reason", safe(reason)
        );
        NotificationEvent event = NotificationEvent.builder()
                .type("EMAIL")
                .to(employee.getEmail())
                .subject("Update on your leave request")
                .templateName("email/leave-rejected")
                .variables(vars)
                .build();
        if (!notificationProducer.publish(event)) {
            emailService.ifPresent(svc -> svc.sendTemplatedEmail(
                    employee.getEmail(), "Update on your leave request",
                    "email/leave-rejected", vars));
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String localPart(String email) {
        if (email == null) return "there";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
