package com.admtechhub.maestrohr.document;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.DocumentExpiryQueries;
import com.admtechhub.maestrohr.platform.DocumentExpiryQueries.ExpiringDocumentRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily sweep that warns HR about employee documents nearing expiry. A document is flagged when
 * its {@code expiry_date} is exactly 30 days or exactly 7 days out — two discrete reminders
 * rather than a daily nag.
 *
 * <p>Multi-tenant safety mirrors {@link com.admtechhub.maestrohr.subscription.SubscriptionLapseJob}:
 * the candidate scan runs through the privileged datasource ({@link DocumentExpiryQueries}) because
 * the scheduler has no tenant session and the RLS-enforced primary role would see nothing. The job
 * then groups candidates by tenant and binds {@link TenantContext} <em>per tenant</em> in a
 * {@code try/finally}, so one tenant's context never leaks into another's and a failure on one
 * tenant does not abort the rest.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentExpiryAlertJob {

    private static final int FIRST_ALERT_DAYS = 30;
    private static final int FINAL_ALERT_DAYS = 7;

    private final DocumentExpiryQueries documentExpiryQueries;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** Runs at 07:00 every day (server time) — before the working day, after the overnight jobs. */
    @Scheduled(cron = "0 0 7 * * *")
    public void alertExpiringDocuments() {
        LocalDate today = LocalDate.now();
        List<LocalDate> horizons = List.of(today.plusDays(FIRST_ALERT_DAYS), today.plusDays(FINAL_ALERT_DAYS));

        List<ExpiringDocumentRow> rows = documentExpiryQueries.findExpiringOn(horizons);
        if (rows.isEmpty()) {
            log.debug("Document expiry sweep: nothing expiring at the {}/{}-day horizons",
                    FIRST_ALERT_DAYS, FINAL_ALERT_DAYS);
            return;
        }

        // Group by tenant (in-memory; no session needed) so each tenant is processed under one bind.
        Map<UUID, List<ExpiringDocumentRow>> byTenant = new LinkedHashMap<>();
        for (ExpiringDocumentRow row : rows) {
            byTenant.computeIfAbsent(row.tenantId(), k -> new java.util.ArrayList<>()).add(row);
        }

        log.info("Document expiry sweep: {} document(s) across {} tenant(s)", rows.size(), byTenant.size());
        int notifications = 0;
        for (Map.Entry<UUID, List<ExpiringDocumentRow>> entry : byTenant.entrySet()) {
            UUID tenantId = entry.getKey();
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                notifications += notifyTenantHr(tenantId, entry.getValue(), today);
            } catch (Exception e) {
                // One tenant's failure must not stop the sweep for the others.
                log.error("Document expiry alert failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Document expiry sweep complete: {} notification(s) sent", notifications);
    }

    /** Notify every active HR_ADMIN in the tenant about each expiring document. */
    private int notifyTenantHr(UUID tenantId, List<ExpiringDocumentRow> docs, LocalDate today) {
        List<String> hrEmails = userRepository.findActiveEmailsByTenantIdAndRole(tenantId, UserRole.HR_ADMIN);
        if (hrEmails.isEmpty()) {
            log.debug("Tenant {} has {} expiring document(s) but no active HR_ADMIN to notify",
                    tenantId, docs.size());
            return 0;
        }

        int sent = 0;
        for (ExpiringDocumentRow doc : docs) {
            long days = ChronoUnit.DAYS.between(today, doc.expiryDate());
            String title = "Document expiring in " + days + " day" + (days == 1 ? "" : "s");
            String message = String.format("%s (%s) expires on %s.",
                    doc.fileName(), doc.documentType(), doc.expiryDate());
            String link = "/employees/" + doc.employeeId();
            for (String email : hrEmails) {
                notificationService.createInAppNotification(email, "DOCUMENT_EXPIRING", title, message, link);
                sent++;
            }
        }
        return sent;
    }
}
