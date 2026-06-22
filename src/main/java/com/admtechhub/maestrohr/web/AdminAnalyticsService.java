package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminAnalyticsQueries;
import com.admtechhub.maestrohr.platform.AdminBillingQueries;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the {@link AdminAnalyticsView} for the super-admin analytics console (Feature 3).
 *
 * <p>Chart series come from {@link AdminAnalyticsQueries} (cross-tenant privileged reads). The KPI
 * "current MRR" and the avg-per-tenant figure are computed the same way as the dashboard / billing
 * console — Σ each active tenant's {@code pricing_config} MONTHLY price ({@link PricingService} ×
 * {@link AdminBillingQueries#findActivePlanCounts()}) — so every MRR number on the platform agrees.
 */
@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private static final String MONTHLY = "MONTHLY";

    /** "2026-03" → "Mar 26" for the time-series axes. */
    private static final DateTimeFormatter AXIS_FMT =
            DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
    /** "2026-03" → "Mar 2026" for the fastest-growing-month KPI. */
    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final AdminAnalyticsQueries adminAnalyticsQueries;
    private final AdminBillingQueries adminBillingQueries;
    private final PricingService pricingService;

    public AdminAnalyticsView build() {
        // ── Time series ───────────────────────────────────────────────────────
        List<String> growthLabels = new ArrayList<>();
        List<Long> growthData = new ArrayList<>();
        List<String> rawGrowthMonths = new ArrayList<>();
        for (Object[] row : adminAnalyticsQueries.tenantGrowthByMonth()) {
            String ym = (String) row[0];
            rawGrowthMonths.add(ym);
            growthLabels.add(formatAxis(ym));
            growthData.add((Long) row[1]);
        }

        List<String> mrrLabels = new ArrayList<>();
        List<Long> mrrData = new ArrayList<>();
        for (Object[] row : adminAnalyticsQueries.mrrByMonth()) {
            mrrLabels.add(formatAxis((String) row[0]));
            mrrData.add((Long) row[1] / 100);   // kobo → naira for the axis
        }

        // ── Distributions ─────────────────────────────────────────────────────
        List<String> planLabels = new ArrayList<>();
        List<Long> planData = new ArrayList<>();
        long totalTenants = 0L;
        for (Object[] row : adminAnalyticsQueries.planDistribution()) {
            planLabels.add(displayPlan((String) row[0]));
            long count = (Long) row[1];
            planData.add(count);
            totalTenants += count;
        }

        List<String> statusLabels = new ArrayList<>();
        List<Long> statusData = new ArrayList<>();
        for (Object[] row : adminAnalyticsQueries.subscriptionStatusDistribution()) {
            statusLabels.add(displayStatus((String) row[0]));
            statusData.add((Long) row[1]);
        }

        // ── KPI figures ───────────────────────────────────────────────────────
        long totalRevenueKobo = adminAnalyticsQueries.totalPaidRevenueKobo();

        long activeTenantCount = 0L;
        long currentMrrKobo = 0L;
        Map<String, Map<String, Long>> prices = pricingService.getAllPrices();
        Map<String, Long> activePlanCounts = new HashMap<>();
        for (AdminBillingQueries.PlanCountRow row : adminBillingQueries.findActivePlanCounts()) {
            activePlanCounts.put(row.plan(), row.count());
        }
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            long count = activePlanCounts.getOrDefault(plan.name(), 0L);
            activeTenantCount += count;
            Map<String, Long> planPrices = prices.get(plan.name());
            long monthlyKobo = planPrices != null ? planPrices.getOrDefault(MONTHLY, 0L) : 0L;
            currentMrrKobo += monthlyKobo * count;
        }
        long avgMrrPerTenantKobo = activeTenantCount > 0 ? currentMrrKobo / activeTenantCount : 0L;

        return new AdminAnalyticsView(
                totalTenants,
                totalRevenueKobo,
                formatNairaCompact(totalRevenueKobo),
                avgMrrPerTenantKobo,
                formatNairaFull(avgMrrPerTenantKobo),
                fastestGrowingMonth(rawGrowthMonths, growthData),
                growthLabels, growthData,
                mrrLabels, mrrData,
                planLabels, planData,
                statusLabels, statusData);
    }

    /**
     * The month with the largest month-over-month jump in cumulative tenants. Deltas start at the
     * second point — month 0 carries every tenant created before the window, so it isn't "growth".
     * Returns "—" when no month grew.
     */
    private String fastestGrowingMonth(List<String> months, List<Long> cumulative) {
        long bestDelta = 0L;
        int bestIdx = -1;
        for (int i = 1; i < cumulative.size(); i++) {
            long delta = cumulative.get(i) - cumulative.get(i - 1);
            if (delta > bestDelta) {
                bestDelta = delta;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) return "—";
        return YearMonth.parse(months.get(bestIdx)).format(MONTH_FMT);
    }

    // ── formatting helpers ────────────────────────────────────────────────────

    private String formatAxis(String yyyyMm) {
        return YearMonth.parse(yyyyMm).format(AXIS_FMT);
    }

    private String displayPlan(String plan) {
        try {
            return SubscriptionPlan.valueOf(plan).getDisplayName();
        } catch (IllegalArgumentException | NullPointerException e) {
            return plan != null ? plan : "Unknown";
        }
    }

    /** "PAST_DUE" → "Past Due". */
    private String displayStatus(String status) {
        if (status == null || status.isBlank()) return "Unknown";
        String[] words = status.toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /** Compact NGN from a kobo amount: "₦1.25M", "₦125K", "₦950". */
    private String formatNairaCompact(long kobo) {
        double naira = kobo / 100.0;
        if (naira >= 1_000_000_000d) return "₦" + trim(naira / 1_000_000_000d) + "B";
        if (naira >= 1_000_000d) return "₦" + trim(naira / 1_000_000d) + "M";
        if (naira >= 1_000d) return "₦" + trim(naira / 1_000d) + "K";
        return String.format(Locale.ENGLISH, "₦%,.0f", naira);
    }

    /** Full NGN with thousands separators: "₦62,500". */
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
}
