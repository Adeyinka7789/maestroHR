package com.admtechhub.maestrohr.retirement;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Daily sweep that notifies HR_ADMIN/SYSTEM_ADMIN users when an active employee crosses a
 * configured retirement-notification threshold (e.g. 180 or 30 days before their estimated
 * retirement date).
 *
 * <p>Multi-tenant safety: unlike {@link com.admtechhub.maestrohr.document.DocumentExpiryAlertJob}
 * / {@link com.admtechhub.maestrohr.subscription.SubscriptionLapseJob} (which scan candidates
 * cross-tenant through a privileged datasource because their target rows aren't reachable any
 * other way), this job's per-tenant work — resolving the {@link RetirementPolicy} and reading
 * that tenant's employees — only needs normal RLS-scoped repositories, so it iterates tenants
 * directly ({@link TenantRepository#findAll()} is unrestricted since {@code tenants} isn't
 * itself tenant-scoped) and binds {@link TenantContext} <em>per tenant</em> in a
 * {@code try/finally}, mirroring the same one-bind-per-iteration discipline as those jobs so one
 * tenant's context never leaks into another's and a failure on one tenant does not abort the rest.
 *
 * <p>Cron slot (08:00 daily) mirrors {@link com.admtechhub.maestrohr.jobs.ProbationEndAlertJob},
 * the closest existing analog (a days-before-a-date HR alert).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RetirementNotificationJob {

    /**
     * A crossed threshold older than this many days is treated as stale and skipped rather than
     * notified. Without this bound, a threshold that was never fired because the job was down
     * (deploy, crash, etc.) would otherwise fire — correctly — the moment the job comes back, but
     * an extended outage (weeks) would then dump a backlog of long-past "approaching retirement"
     * alerts that are no longer actionable at that vintage. 30 days keeps the window wide enough
     * to absorb a normal deploy/downtime gap (the stated goal) while bounding the backlog;
     * it also matches the magnitude already used for the same purpose in
     * {@link com.admtechhub.maestrohr.document.DocumentExpiryAlertJob}'s 30-day horizon.
     */
    private static final int STALE_WINDOW_DAYS = 30;

    private final TenantRepository tenantRepository;
    private final EmployeeRepository employeeRepository;
    private final RetirementPolicyService retirementPolicyService;
    private final RetirementNotificationLogRepository notificationLogRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "0 0 8 * * *")
    public void notifyApproachingRetirements() {
        List<Tenant> tenants = tenantRepository.findAll().stream().filter(Tenant::isActive).toList();
        if (tenants.isEmpty()) {
            log.debug("Retirement notification sweep: no active tenants");
            return;
        }

        log.info("Retirement notification sweep: checking {} active tenant(s)", tenants.size());
        int sent = 0;
        for (Tenant tenant : tenants) {
            UUID tenantId = tenant.getId();
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                sent += processTenant(tenantId);
            } catch (Exception e) {
                // One tenant's failure must not stop the sweep for the others.
                log.error("Retirement notification sweep failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Retirement notification sweep complete: {} notification(s) sent", sent);
    }

    /**
     * Not {@code @Transactional} — each repository call below (find, exists, save) already runs
     * in its own implicit transaction via Spring Data JPA, matching {@code ProbationEndAlertJob}
     * / {@code DocumentExpiryAlertJob}'s per-tenant method. An explicit {@code @Transactional}
     * here would be a no-op anyway: this method is only ever reached via self-invocation from
     * {@link #notifyApproachingRetirements()} within the same bean, which bypasses the Spring AOP
     * proxy that {@code @Transactional} relies on.
     */
    private int processTenant(UUID tenantId) {
        RetirementPolicy policy = retirementPolicyService.getOrCreateDefault(tenantId);
        List<Integer> thresholds = retirementPolicyService.parseThresholds(policy.getNotificationThresholdDays());
        if (thresholds.isEmpty()) {
            return 0;
        }

        List<Employee> employees = employeeRepository.findByStatus(EmployeeStatus.ACTIVE).stream()
                .filter(e -> e.getDateOfBirth() != null)
                .toList();
        if (employees.isEmpty()) {
            return 0;
        }

        List<String> recipients = hrAndSystemAdminEmails(tenantId);
        if (recipients.isEmpty()) {
            log.debug("Tenant {} has {} retirement-eligible employee(s) but no active HR_ADMIN/SYSTEM_ADMIN to notify",
                    tenantId, employees.size());
            return 0;
        }

        LocalDate today = LocalDate.now(clock);
        int sent = 0;
        for (Employee employee : employees) {
            Optional<LocalDate> retirementDate = retirementPolicyService.getEstimatedRetirementDate(employee);
            if (retirementDate.isEmpty()) {
                continue;
            }
            for (Integer thresholdDays : thresholds) {
                if (shouldNotify(employee.getId(), retirementDate.get(), thresholdDays, today)) {
                    notify(recipients, employee, retirementDate.get(), thresholdDays);
                    sent++;
                }
            }
        }
        return sent;
    }

    /**
     * True when the employee just crossed {@code thresholdDays} before their retirement date —
     * the threshold date is today or in the recent past (not exact-day-only, so a job that missed
     * its exact calendar day still catches up), bounded to {@link #STALE_WINDOW_DAYS} to avoid a
     * backlog flood — and no log row exists yet for this (employee, threshold) pair.
     */
    private boolean shouldNotify(UUID employeeId, LocalDate retirementDate, int thresholdDays, LocalDate today) {
        LocalDate thresholdDate = retirementDate.minusDays(thresholdDays);
        long daysSinceThreshold = ChronoUnit.DAYS.between(thresholdDate, today);
        if (daysSinceThreshold < 0 || daysSinceThreshold > STALE_WINDOW_DAYS) {
            return false;
        }
        return !notificationLogRepository.existsByEmployeeIdAndThresholdDays(employeeId, thresholdDays);
    }

    private void notify(List<String> recipients, Employee employee, LocalDate retirementDate, int thresholdDays) {
        String title = "Retirement approaching: " + employee.getFullName();
        String message = employee.getFullName() + " is approaching retirement (estimated "
                + retirementDate + ", " + thresholdDays + " days from now).";
        String link = "/employees/" + employee.getId();
        for (String email : recipients) {
            notificationService.createInAppNotification(email, "RETIREMENT_APPROACHING", title, message, link);
        }

        notificationLogRepository.save(RetirementNotificationLog.builder()
                .tenant(employee.getTenant())
                .employee(employee)
                .thresholdDays(thresholdDays)
                .notifiedAt(OffsetDateTime.now(clock))
                .build());
    }

    /** Active HR_ADMIN + SYSTEM_ADMIN emails for the tenant, de-duplicated. */
    private List<String> hrAndSystemAdminEmails(UUID tenantId) {
        Set<String> emails = new LinkedHashSet<>(userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.HR_ADMIN));
        emails.addAll(userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.SYSTEM_ADMIN));
        return List.copyOf(emails);
    }
}
