package com.admtechhub.maestrohr.notification;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final PayslipGenerator payslipGenerator;
    private final TermiiClient termiiClient;
    private final InAppNotificationRepository inAppNotificationRepository;

    @Autowired(required = false)
    private EmailService emailService;

    @Async
    public void sendPayslipNotification(PayrollEntry entry, Employee employee, String period) {
        log.info("Generating payslip for employee: {}", employee.getEmployeeNumber());

        byte[] payslipPdf = payslipGenerator.generatePayslip(entry, employee, period);

        if (payslipPdf != null) {
            // Send email if email service is available
            if (emailService != null) {
                String subject = "Payslip for " + period;
                String body = String.format(
                        "<h3>Dear %s,</h3>" +
                                "<p>Your payslip for %s is attached to this email.</p>" +
                                "<p><strong>Net Salary:</strong> ₦%.2f</p>" +
                                "<p>Thank you.</p>",
                        employee.getFullName(), period, entry.getNetSalary() / 100.0
                );

                emailService.sendEmailWithAttachment(
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
}
