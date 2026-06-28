package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.ProbationRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily 08:00 sweep that notifies HR_ADMINs when an active employee's probation period
 * ends in exactly {@value #ALERT_DAYS_BEFORE} days, giving HR time to schedule a review.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProbationEndAlertJob {

    private static final int ALERT_DAYS_BEFORE = 7;

    private final JobSweepQueries jobSweepQueries;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 8 * * *")
    public void alertProbationEnding() {
        LocalDate targetDate = LocalDate.now().plusDays(ALERT_DAYS_BEFORE);
        List<ProbationRow> candidates = jobSweepQueries.findProbationEndingSoon(targetDate);
        if (candidates.isEmpty()) {
            log.debug("Probation end alert: no employees ending probation on {}", targetDate);
            return;
        }

        Map<UUID, List<ProbationRow>> byTenant = new LinkedHashMap<>();
        for (ProbationRow row : candidates) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new ArrayList<>()).add(row);
        }

        log.info("Probation end alert: {} employee(s) across {} tenant(s)", candidates.size(), byTenant.size());
        int sent = 0;
        for (Map.Entry<UUID, List<ProbationRow>> entry : byTenant.entrySet()) {
            UUID tenantId = entry.getKey();
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                sent += notifyTenant(tenantId, entry.getValue());
            } catch (Exception e) {
                log.error("Probation end alert failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Probation end alert complete: {} notification(s) sent", sent);
    }

    private int notifyTenant(UUID tenantId, List<ProbationRow> employees) {
        List<String> hrEmails = userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.HR_ADMIN);
        if (hrEmails.isEmpty()) {
            log.debug("Tenant {} has {} probation(s) ending in {} days but no active HR_ADMIN",
                    tenantId, employees.size(), ALERT_DAYS_BEFORE);
            return 0;
        }
        int count = 0;
        for (ProbationRow emp : employees) {
            String name = emp.firstName() + " " + emp.lastName();
            String title = "Probation ending in " + ALERT_DAYS_BEFORE + " days: " + name;
            String message = name + "'s probation period ends in " + ALERT_DAYS_BEFORE
                    + " days. Please schedule a performance review.";
            String link = "/employees/" + emp.employeeId();
            for (String email : hrEmails) {
                notificationService.createInAppNotification(email, "PROBATION_ENDING", title, message, link);
                count++;
            }
        }
        return count;
    }
}
