package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the {@link SuperAdminDashboardView} for the platform (super-admin) dashboard.
 *
 * <p>Every figure is a cross-tenant aggregate, so the reads go through the privileged,
 * RLS-bypassing datasource exposed by {@link AdminStatsQueries} — the tenant-scoped JPA path
 * would collapse them to the admin's own tenant. MRR is computed from the {@code pricing_config}
 * single source of truth ({@link PricingService}), summing each active tenant's monthly plan price.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminDashboardService {

    private static final int RECENT_TENANTS_LIMIT = 6;
    private static final int RECENT_LOGS_LIMIT = 5;
    private static final String MONTHLY = "MONTHLY";

    /** No real health probe yet — the card shows a static "all good" until one exists. */
    private static final int SYSTEM_HEALTH_PLACEHOLDER = 100;

    private static final DateTimeFormatter JOINED_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter LOG_DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final AdminStatsQueries adminStatsQueries;
    private final PricingService pricingService;

    public SuperAdminDashboardView build() {
        long totalTenants = adminStatsQueries.countTenants();
        long activeSubscriptions = adminStatsQueries.countActiveTenants();

        return new SuperAdminDashboardView(
                totalTenants,
                activeSubscriptions,
                formatNairaCompact(computeMrrKobo()),
                SYSTEM_HEALTH_PLACEHOLDER,
                buildRecentTenants(),
                buildRecentLogs());
    }

    /**
     * MRR = Σ monthly plan price of every <em>active</em> tenant, from {@code pricing_config}.
     * Prices are fetched once into a map (1 query) rather than per-tenant. FREE_TRIAL and any
     * plan without a configured price contribute 0.
     */
    private long computeMrrKobo() {
        Map<String, Map<String, Long>> prices = pricingService.getAllPrices();
        long totalKobo = 0L;
        for (TenantWithUserCountDTO tenant : adminStatsQueries.findAllTenantsWithUserCount()) {
            if (!tenant.isActive()) {
                continue;
            }
            Map<String, Long> planPrices = prices.get(tenant.getSubscriptionPlan().name());
            if (planPrices != null) {
                totalKobo += planPrices.getOrDefault(MONTHLY, 0L);
            }
        }
        return totalKobo;
    }

    private List<SuperAdminDashboardView.TenantRow> buildRecentTenants() {
        List<SuperAdminDashboardView.TenantRow> rows = new ArrayList<>();
        for (AdminStatsQueries.RecentTenantRow t : adminStatsQueries.findRecentTenants(RECENT_TENANTS_LIMIT)) {
            rows.add(new SuperAdminDashboardView.TenantRow(
                    initials(t.companyName()),
                    t.companyName(),
                    blankToDash(t.rcNumber()),
                    t.subscriptionPlan(),
                    t.createdAt() != null ? t.createdAt().format(JOINED_FMT) : "—",
                    t.active(),
                    t.active() ? "ACTIVE" : "INACTIVE",
                    t.userCount()));
        }
        return rows;
    }

    private List<SuperAdminDashboardView.LogRow> buildRecentLogs() {
        List<SuperAdminDashboardView.LogRow> rows = new ArrayList<>();
        for (AdminStatsQueries.RecentLogRow log : adminStatsQueries.findRecentAuditLogs(RECENT_LOGS_LIMIT)) {
            rows.add(new SuperAdminDashboardView.LogRow(
                    iconFor(log.statusCode()),
                    kindFor(log.statusCode()),
                    humanize(log.action()),
                    buildLogSubtitle(log),
                    relativeWhen(log.createdAt())));
        }
        return rows;
    }

    private String buildLogSubtitle(AdminStatsQueries.RecentLogRow log) {
        String actor = (log.actorEmail() != null && !log.actorEmail().isBlank())
                ? log.actorEmail() : "system";
        String method = log.httpMethod() != null ? log.httpMethod() : "";
        String path = log.requestPath() != null ? log.requestPath() : "";
        return (actor + " · " + method + " " + path).trim();
    }

    // ── formatting helpers ────────────────────────────────────────────────────

    /** Status-code → severity colour bucket used by the log feed. */
    private String kindFor(int statusCode) {
        if (statusCode >= 500) return "error";
        if (statusCode >= 400) return "warn";
        return "success";
    }

    private String iconFor(int statusCode) {
        if (statusCode >= 500) return "error";
        if (statusCode >= 400) return "warning";
        return "info";
    }

    /** Compact NGN from a kobo amount: "₦1.25M", "₦125K", "₦950". */
    private String formatNairaCompact(long kobo) {
        double naira = kobo / 100.0;
        if (naira >= 1_000_000_000d) return "₦" + trim(naira / 1_000_000_000d) + "B";
        if (naira >= 1_000_000d) return "₦" + trim(naira / 1_000_000d) + "M";
        if (naira >= 1_000d) return "₦" + trim(naira / 1_000d) + "K";
        return String.format(Locale.ENGLISH, "₦%,.0f", naira);
    }

    /** Two decimals, but drop a trailing ".00" / ".0" so "1.00M" renders as "1M". */
    private String trim(double value) {
        String s = String.format(Locale.ENGLISH, "%.2f", value);
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }

    private String relativeWhen(OffsetDateTime when) {
        if (when == null) return "—";
        OffsetDateTime now = OffsetDateTime.now();
        long minutes = ChronoUnit.MINUTES.between(when, now);
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + (minutes == 1 ? " min ago" : " mins ago");
        long hours = ChronoUnit.HOURS.between(when, now);
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = ChronoUnit.DAYS.between(when, now);
        if (days < 7) return days + (days == 1 ? " day ago" : " days ago");
        return when.format(LOG_DATE_FMT);
    }

    /** First letters of the first two words of the company name (uppercased), e.g. "Apex Logistics" → "AL". */
    private String initials(String companyName) {
        if (companyName == null || companyName.isBlank()) return "—";
        String[] words = companyName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (sb.length() == 2) break;
            }
        }
        return sb.toString();
    }

    private String blankToDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

    /** "CREATE_EMPLOYEE" / "create-employee" → "Create Employee". */
    private String humanize(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        String spaced = raw.replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ENGLISH);
        StringBuilder sb = new StringBuilder(spaced.length());
        boolean cap = true;
        for (char c : spaced.toCharArray()) {
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
