package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.EmailService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.TrialExpiryRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily job that emails SYSTEM_ADMINs of tenants whose trial is ending in 7, 3, or 1 days.
 * Uses the privileged datasource to scan {@code tenant_subscriptions} cross-tenant, then binds
 * {@link TenantContext} per-tenant to resolve admin emails via the normal (RLS-enforced) path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrialExpiryReminderJob {

    private final JobSweepQueries jobSweepQueries;
    private final UserRepository userRepository;
    private final Optional<EmailService> emailService;

    @Scheduled(cron = "0 0 9 * * *")
    public void remindTrialExpiry() {
        List<TrialExpiryRow> candidates = jobSweepQueries.findTrialingTenantsExpiringSoon();
        if (candidates.isEmpty()) {
            log.debug("Trial expiry reminder: no trials expiring in 1/3/7 days");
            return;
        }
        log.info("Trial expiry reminder: {} tenant(s) with expiring trials", candidates.size());
        int sent = 0;
        for (TrialExpiryRow row : candidates) {
            try {
                TenantContext.setCurrentTenant(row.tenantId().toString());
                sent += notifyTenant(row.tenantId(), row.daysRemaining());
            } catch (Exception e) {
                log.error("Trial expiry reminder failed for tenant {}: {}", row.tenantId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Trial expiry reminder complete: {} email(s) sent", sent);
    }

    private int notifyTenant(UUID tenantId, int daysRemaining) {
        if (emailService.isEmpty()) {
            log.debug("Email service unavailable — skipping trial expiry reminder for tenant {}", tenantId);
            return 0;
        }
        List<String> adminEmails = userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.SYSTEM_ADMIN);
        if (adminEmails.isEmpty()) {
            log.debug("Tenant {} has no active SYSTEM_ADMIN for trial expiry reminder", tenantId);
            return 0;
        }
        String dayWord = daysRemaining == 1 ? "day" : "days";
        String subject = "Your MaestroHR trial ends in " + daysRemaining + " " + dayWord;
        String body = "Your MaestroHR trial ends in " + daysRemaining + " " + dayWord + ".\n\n"
                + "Upgrade your plan to continue enjoying uninterrupted access to payroll, "
                + "leave management, and all MaestroHR features.\n\n"
                + "The MaestroHR Team";
        int count = 0;
        for (String email : adminEmails) {
            emailService.get().sendEmailWithAttachment(email, subject, body, null, null);
            count++;
        }
        return count;
    }
}
