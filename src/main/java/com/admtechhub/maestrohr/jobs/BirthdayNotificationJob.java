package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.platform.JobSweepQueries.BirthdayRow;
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
 * Daily 08:00 sweep that notifies HR_ADMINs about employee birthdays today.
 * Scans all active employees across all tenants via the privileged datasource, then
 * binds {@link TenantContext} per-tenant to write in-app notifications.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BirthdayNotificationJob {

    private final JobSweepQueries jobSweepQueries;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmployeeRepository employeeRepository;

    @Scheduled(cron = "0 0 8 * * *")
    public void notifyBirthdays() {
        LocalDate today = LocalDate.now();
        List<BirthdayRow> birthdays = jobSweepQueries.findBirthdaysToday(today.getMonthValue(), today.getDayOfMonth());
        if (birthdays.isEmpty()) {
            log.debug("Birthday notification: no employee birthdays today");
            return;
        }

        Map<UUID, List<BirthdayRow>> byTenant = new LinkedHashMap<>();
        for (BirthdayRow row : birthdays) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new ArrayList<>()).add(row);
        }

        log.info("Birthday notification: {} birthday(s) across {} tenant(s)", birthdays.size(), byTenant.size());
        int sent = 0;
        for (Map.Entry<UUID, List<BirthdayRow>> entry : byTenant.entrySet()) {
            UUID tenantId = entry.getKey();
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                sent += notifyTenant(tenantId, entry.getValue());
            } catch (Exception e) {
                log.error("Birthday notification failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Birthday notification complete: {} notification(s) sent", sent);
    }

    private int notifyTenant(UUID tenantId, List<BirthdayRow> employees) {
        List<String> hrEmails = userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.HR_ADMIN);
        int count = 0;
        for (BirthdayRow emp : employees) {
            String name = emp.firstName() + " " + emp.lastName();
            String title = "Birthday today: " + name;
            String message = name + "'s birthday is today!";
            String link = "/employees/" + emp.employeeId();

            // Notify HR admins
            for (String email : hrEmails) {
                notificationService.createInAppNotification(email, "EMPLOYEE_BIRTHDAY", title, message, link);
                count++;
            }

            // Send birthday wish directly to the employee
            employeeRepository.findById(emp.employeeId()).ifPresent(employee -> {
                String tenantName = employee.getTenant() != null ? employee.getTenant().getCompanyName() : "the team";
                notificationService.sendBirthdayWish(employee, tenantName);
            });
        }
        if (hrEmails.isEmpty()) {
            log.debug("Tenant {} has {} birthday(s) today but no active HR_ADMIN to notify", tenantId, employees.size());
        }
        return count;
    }
}
