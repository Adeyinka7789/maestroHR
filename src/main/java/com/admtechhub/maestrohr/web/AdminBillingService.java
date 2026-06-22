package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminBillingQueries;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the {@link AdminBillingView} for the super-admin billing console.
 *
 * <p>Every figure is a cross-tenant aggregate read through the privileged datasource
 * ({@link AdminBillingQueries}). MRR is computed here from the {@code pricing_config} single source
 * of truth ({@link PricingService}) — Σ each active tenant's MONTHLY plan price — matching
 * {@code SuperAdminDashboardService} so the dashboard MRR card and this page agree. Card counts
 * (active / trialing / past-due) come from the {@code tenant_subscriptions.status} breakdown.
 */
@Service
@RequiredArgsConstructor
public class AdminBillingService {

    private static final int RECENT_INVOICES_LIMIT = 8;
    private static final String MONTHLY = "MONTHLY";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final AdminBillingQueries adminBillingQueries;
    private final PricingService pricingService;

    public AdminBillingView buildOverview() {
        Map<String, Map<String, Long>> prices = pricingService.getAllPrices();

        // MRR by plan: Σ MONTHLY pricing_config price × active-tenant count, per plan.
        Map<String, Long> planCounts = new HashMap<>();
        for (AdminBillingQueries.PlanCountRow row : adminBillingQueries.findActivePlanCounts()) {
            planCounts.put(row.plan(), row.count());
        }
        List<AdminBillingView.MrrByPlanRow> mrrByPlan = new ArrayList<>();
        long totalMrrKobo = 0L;
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            long count = planCounts.getOrDefault(plan.name(), 0L);
            Map<String, Long> planPrices = prices.get(plan.name());
            long monthlyKobo = planPrices != null ? planPrices.getOrDefault(MONTHLY, 0L) : 0L;
            long revenueKobo = monthlyKobo * count;
            totalMrrKobo += revenueKobo;
            mrrByPlan.add(new AdminBillingView.MrrByPlanRow(
                    plan.name(), count, revenueKobo, formatNairaFull(revenueKobo)));
        }

        // Status card counts.
        Map<String, Long> statusCounts = new HashMap<>();
        for (AdminBillingQueries.StatusCountRow row : adminBillingQueries.findStatusCounts()) {
            statusCounts.put(row.status(), row.count());
        }
        long activeCount = statusCounts.getOrDefault("ACTIVE", 0L);
        long trialingCount = statusCounts.getOrDefault("TRIALING", 0L);
        long pastDueCount = statusCounts.getOrDefault("PAST_DUE", 0L)
                + statusCounts.getOrDefault("EXPIRED", 0L);

        return new AdminBillingView(
                totalMrrKobo,
                formatNairaCompact(totalMrrKobo),
                activeCount,
                trialingCount,
                pastDueCount,
                mrrByPlan,
                buildPastDue(),
                buildRecentInvoices());
    }

    private List<AdminBillingView.PastDueRow> buildPastDue() {
        List<AdminBillingView.PastDueRow> rows = new ArrayList<>();
        for (AdminBillingQueries.PastDueRow t : adminBillingQueries.findPastDueTenants()) {
            rows.add(new AdminBillingView.PastDueRow(
                    t.tenantId(),
                    t.companyName(),
                    t.plan(),
                    t.status(),
                    formatDate(t.currentPeriodEnd())));
        }
        return rows;
    }

    private List<AdminBillingView.InvoiceRow> buildRecentInvoices() {
        List<AdminBillingView.InvoiceRow> rows = new ArrayList<>();
        for (AdminBillingQueries.PlatformInvoiceRow inv : adminBillingQueries.findRecentInvoices(RECENT_INVOICES_LIMIT)) {
            rows.add(new AdminBillingView.InvoiceRow(
                    inv.id(),
                    inv.tenantId(),
                    inv.companyName(),
                    formatNairaFull(inv.amountKobo()),
                    inv.status(),
                    inv.plan(),
                    formatDate(inv.createdAt())));
        }
        return rows;
    }

    // ── formatting helpers ────────────────────────────────────────────────────

    /** Compact NGN from a kobo amount: "₦1.25M", "₦125K", "₦950". */
    private String formatNairaCompact(long kobo) {
        double naira = kobo / 100.0;
        if (naira >= 1_000_000_000d) return "₦" + trim(naira / 1_000_000_000d) + "B";
        if (naira >= 1_000_000d) return "₦" + trim(naira / 1_000_000d) + "M";
        if (naira >= 1_000d) return "₦" + trim(naira / 1_000d) + "K";
        return String.format(Locale.ENGLISH, "₦%,.0f", naira);
    }

    /** Full NGN with thousands separators: "₦1,500,000". */
    private String formatNairaFull(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    /** Two decimals, dropping a trailing ".00" / ".0" so "1.00M" renders as "1M". */
    private String trim(double value) {
        String s = String.format(Locale.ENGLISH, "%.2f", value);
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }

    private String formatDate(OffsetDateTime when) {
        return when != null ? when.format(DATE_FMT) : "—";
    }
}
