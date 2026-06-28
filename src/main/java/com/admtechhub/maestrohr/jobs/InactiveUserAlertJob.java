package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.InactiveUsersRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Weekly Monday 09:00 sweep that notifies SYSTEM_ADMINs when their tenant has active users
 * who have not logged in for {@value #INACTIVE_DAYS} or more days. A single aggregated
 * notification is sent per tenant (not one per user) to avoid noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InactiveUserAlertJob {

    private static final int INACTIVE_DAYS = 90;

    private final JobSweepQueries jobSweepQueries;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 9 * * MON")
    public void alertInactiveUsers() {
        List<InactiveUsersRow> rows = jobSweepQueries.findTenantsWithInactiveUsers(INACTIVE_DAYS);
        if (rows.isEmpty()) {
            log.debug("Inactive user alert: no tenants have users inactive for {}+ days", INACTIVE_DAYS);
            return;
        }
        log.info("Inactive user alert: {} tenant(s) with inactive users", rows.size());
        int sent = 0;
        for (InactiveUsersRow row : rows) {
            UUID tenantId = row.tenantId();
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                sent += notifyTenant(tenantId, row.inactiveCount());
            } catch (Exception e) {
                log.error("Inactive user alert failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Inactive user alert complete: {} notification(s) sent", sent);
    }

    private int notifyTenant(UUID tenantId, long inactiveCount) {
        List<String> adminEmails = userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.SYSTEM_ADMIN);
        if (adminEmails.isEmpty()) {
            log.debug("Tenant {} has {} inactive user(s) but no active SYSTEM_ADMIN to notify", tenantId, inactiveCount);
            return 0;
        }
        String title = inactiveCount + " user" + (inactiveCount == 1 ? "" : "s") + " inactive for " + INACTIVE_DAYS + "+ days";
        String message = inactiveCount + " active user" + (inactiveCount == 1 ? " has" : "s have")
                + " not logged in for " + INACTIVE_DAYS + " or more days. "
                + "Consider reviewing their access.";
        String link = "/users";
        int count = 0;
        for (String email : adminEmails) {
            notificationService.createInAppNotification(email, "INACTIVE_USERS", title, message, link);
            count++;
        }
        return count;
    }
}
