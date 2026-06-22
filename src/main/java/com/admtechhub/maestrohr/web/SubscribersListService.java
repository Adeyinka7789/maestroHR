package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Assembles the {@link SubscribersListView} for the super-admin subscribers page.
 *
 * <p>The portfolio spans every tenant, so the reads go through the privileged, RLS-bypassing
 * datasource exposed by {@link AdminStatsQueries} — the tenant-scoped JPA path would collapse
 * them to the admin's own tenant. The full tenant list is fetched once: the stat cards are
 * derived from it (or from dedicated count queries), and the table rows are the same list
 * narrowed by the optional search/status/plan filters, in memory.
 */
@Service
@RequiredArgsConstructor
public class SubscribersListService {

    private static final String TRIAL_PLAN = SubscriptionPlan.FREE_TRIAL.name();
    private static final DateTimeFormatter EXPIRES_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final AdminStatsQueries adminStatsQueries;

    public SubscribersListView build(String search, String status, String plan) {
        String q = norm(search);
        String statusFilter = blankToEmpty(status);
        String planFilter = blankToEmpty(plan);

        List<TenantWithUserCountDTO> all = adminStatsQueries.findAllTenantsWithUserCount();

        long trials = all.stream()
                .filter(t -> t.getSubscriptionPlan() != null && TRIAL_PLAN.equals(t.getSubscriptionPlan().name()))
                .count();

        List<SubscribersListView.Row> rows = new ArrayList<>();
        for (TenantWithUserCountDTO t : all) {
            if (matches(t, q, statusFilter, planFilter)) {
                rows.add(toRow(t));
            }
        }

        return new SubscribersListView(
                adminStatsQueries.countTenants(),
                adminStatsQueries.countActiveTenants(),
                trials,
                adminStatsQueries.countUsers(),
                q,
                statusFilter,
                planFilter,
                rows);
    }

    /** Mirrors the legacy client-side filter: free-text over company/industry/RC/plan, plus status + plan. */
    private boolean matches(TenantWithUserCountDTO t, String q, String statusFilter, String planFilter) {
        if (!q.isEmpty()) {
            String haystack = (safe(t.getCompanyName()) + ' ' + safe(t.getIndustry()) + ' '
                    + safe(t.getRcNumber()) + ' ' + planName(t)).toLowerCase(Locale.ENGLISH);
            if (!haystack.contains(q)) {
                return false;
            }
        }
        if (!statusFilter.isEmpty()) {
            String tenantStatus = t.isActive() ? "Active" : "Inactive";
            if (!tenantStatus.equals(statusFilter)) {
                return false;
            }
        }
        if (!planFilter.isEmpty() && !planName(t).equals(planFilter)) {
            return false;
        }
        return true;
    }

    private SubscribersListView.Row toRow(TenantWithUserCountDTO t) {
        return new SubscribersListView.Row(
                t.getId(),
                safe(t.getCompanyName()),
                blankToDash(t.getIndustry()),
                blankToDash(t.getCompanySize()),
                planName(t),
                t.isActive(),
                t.isActive() ? "Active" : "Inactive",
                t.getSubscriptionExpiresAt() != null ? t.getSubscriptionExpiresAt().format(EXPIRES_FMT) : "—",
                blankToDash(t.getRcNumber()),
                t.getUserCount() != null ? t.getUserCount() : 0L);
    }

    private String planName(TenantWithUserCountDTO t) {
        return t.getSubscriptionPlan() != null ? t.getSubscriptionPlan().name() : "";
    }

    private String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }

    private String blankToEmpty(String value) {
        return (value == null || value.isBlank()) ? "" : value.trim();
    }

    private String blankToDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
