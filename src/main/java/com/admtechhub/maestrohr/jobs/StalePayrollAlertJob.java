package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.StalePayrollRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Daily 08:00 sweep that notifies SYSTEM_ADMINs and FINANCE_OFFICERs when a payroll run
 * has been sitting in APPROVED status for more than {@value #STALE_DAYS} days without being
 * disbursed. Groups multiple stale runs per tenant into one notification loop.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StalePayrollAlertJob {

    private static final int STALE_DAYS = 5;

    private final JobSweepQueries jobSweepQueries;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 8 * * *")
    public void alertStalePayrolls() {
        List<StalePayrollRow> stale = jobSweepQueries.findStaleApprovedPayrolls(STALE_DAYS);
        if (stale.isEmpty()) {
            log.debug("Stale payroll alert: no APPROVED runs older than {} days", STALE_DAYS);
            return;
        }

        Map<UUID, List<StalePayrollRow>> byTenant = new LinkedHashMap<>();
        for (StalePayrollRow row : stale) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new ArrayList<>()).add(row);
        }

        log.info("Stale payroll alert: {} run(s) across {} tenant(s)", stale.size(), byTenant.size());
        int sent = 0;
        for (Map.Entry<UUID, List<StalePayrollRow>> entry : byTenant.entrySet()) {
            UUID tenantId = entry.getKey();
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                sent += notifyTenant(tenantId, entry.getValue());
            } catch (Exception e) {
                log.error("Stale payroll alert failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Stale payroll alert complete: {} notification(s) sent", sent);
    }

    private int notifyTenant(UUID tenantId, List<StalePayrollRow> runs) {
        List<String> recipients = new ArrayList<>();
        for (UserRole role : List.of(UserRole.SYSTEM_ADMIN, UserRole.FINANCE_OFFICER)) {
            recipients.addAll(userRepository.findActiveEmailsByTenantIdAndRole(tenantId, role));
        }
        if (recipients.isEmpty()) {
            log.debug("Tenant {} has {} stale payroll run(s) but no admin/finance user to notify", tenantId, runs.size());
            return 0;
        }
        int count = 0;
        for (StalePayrollRow run : runs) {
            String period = Month.of(run.payrollMonth()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + run.payrollYear();
            String title = period + " payroll approved but not yet disbursed";
            String message = period + " payroll was approved " + STALE_DAYS + " days ago and has not yet been disbursed.";
            String link = "/payroll/" + run.runId();
            for (String email : recipients) {
                notificationService.createInAppNotification(email, "PAYROLL_STALE", title, message, link);
                count++;
            }
        }
        return count;
    }
}
