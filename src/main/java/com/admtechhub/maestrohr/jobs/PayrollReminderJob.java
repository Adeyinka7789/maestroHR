package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.EmailService;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.PayrollPendingRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs on the 25th of each month at 09:00 and reminds SYSTEM_ADMINs and FINANCE_OFFICERs
 * of any tenant that has not yet submitted (or finalized) that month's payroll run.
 * Sends both an in-app notification and an email.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollReminderJob {

    private final JobSweepQueries jobSweepQueries;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final Optional<EmailService> emailService;

    @Scheduled(cron = "0 0 9 25 * *")
    public void remindPayrollProcessing() {
        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        int year = today.getYear();
        String period = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year;

        List<PayrollPendingRow> candidates = jobSweepQueries.findTenantsWithPendingPayroll(month, year);
        if (candidates.isEmpty()) {
            log.debug("Payroll reminder: all active tenants have finalized payroll for {}", period);
            return;
        }
        log.info("Payroll reminder: {} tenant(s) have not finalized {} payroll", candidates.size(), period);
        int notified = 0;
        for (PayrollPendingRow row : candidates) {
            try {
                TenantContext.setCurrentTenant(row.tenantId().toString());
                notified += notifyTenant(row.tenantId(), period);
            } catch (Exception e) {
                log.error("Payroll reminder failed for tenant {}: {}", row.tenantId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Payroll reminder complete: {} notification(s) sent for {}", notified, period);
    }

    private int notifyTenant(UUID tenantId, String period) {
        String title = "Reminder: Process " + period + " payroll";
        String message = "The " + period + " payroll run has not been finalized. Please process it before month end.";
        String link = "/payroll";
        String emailSubject = "MaestroHR: " + period + " payroll reminder";
        String emailBody = message + "\n\nLog in to MaestroHR and visit Payroll to process the run.\n\nThe MaestroHR Team";
        int count = 0;
        for (UserRole role : List.of(UserRole.SYSTEM_ADMIN, UserRole.FINANCE_OFFICER)) {
            List<String> emails = userRepository.findActiveEmailsByTenantIdAndRole(tenantId, role);
            for (String email : emails) {
                notificationService.createInAppNotification(email, "PAYROLL_REMINDER", title, message, link);
                if (emailService.isPresent()) {
                    emailService.get().sendEmailWithAttachment(email, emailSubject, emailBody, null, null);
                }
                count++;
            }
        }
        if (count == 0) {
            log.debug("Tenant {} has no SYSTEM_ADMIN or FINANCE_OFFICER for payroll reminder", tenantId);
        }
        return count;
    }
}
