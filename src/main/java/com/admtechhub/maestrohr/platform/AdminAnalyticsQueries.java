package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SUPERADMIN Feature 3 — privileged cross-tenant aggregates feeding the platform analytics charts.
 *
 * <p>Like {@link AdminStatsQueries} and {@link AdminBillingQueries}, every query here spans
 * <em>all</em> tenants and therefore goes through the privileged ({@code postgres}) datasource:
 * under the RLS-enforced {@code maestro_app} primary the V22/V25 tenant-isolation policies would
 * collapse these to the admin's own tenant (or nothing). Reads only — analytics is view-only.
 *
 * <p>The two time-series ({@link #tenantGrowthByMonth} / {@link #mrrByMonth}) are <em>cumulative</em>
 * over a {@code generate_series} of the last 12 month-starts: each month counts every tenant created
 * on or before that month's end. MRR follows the platform convention — Σ each active tenant's
 * {@code pricing_config} MONTHLY price — so the latest bar reconciles with the dashboard / billing
 * MRR card. (Historical status isn't tracked, so a currently-active tenant is assumed active back to
 * its join month; this is the standard approximation for a derived MRR trend.)
 *
 * <p>Each method returns {@code List<Object[]>} as specified for the analytics layer; the service
 * maps these into the Chart.js-ready {@code AdminAnalyticsView}.
 */
@Repository
public class AdminAnalyticsQueries {

    /** A 12-row series of month-starts, oldest first, as {@code (YYYY-MM label, month_start)}. */
    private static final String MONTHS_CTE =
            "WITH months AS ("
                    + "  SELECT to_char(d, 'YYYY-MM') AS ym, d AS month_start "
                    + "  FROM generate_series("
                    + "         date_trunc('month', now()) - interval '11 months', "
                    + "         date_trunc('month', now()), "
                    + "         interval '1 month') AS d"
                    + ") ";

    private final JdbcTemplate jdbc;

    public AdminAnalyticsQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cumulative tenant registrations at each of the last 12 month-ends → {@code [yyyy-MM, count]}. */
    public List<Object[]> tenantGrowthByMonth() {
        return jdbc.query(
                MONTHS_CTE
                        + "SELECT m.ym, COUNT(t.id) AS cnt "
                        + "FROM months m "
                        + "LEFT JOIN tenants t ON t.created_at < m.month_start + interval '1 month' "
                        + "GROUP BY m.ym, m.month_start "
                        + "ORDER BY m.month_start",
                (rs, n) -> new Object[]{rs.getString("ym"), rs.getLong("cnt")});
    }

    /**
     * Cumulative MRR (kobo) at each of the last 12 month-ends → {@code [yyyy-MM, mrrKobo]}.
     * Σ the {@code pricing_config} MONTHLY price of every <em>active</em> tenant created on or
     * before the month, so the final month equals the dashboard / billing MRR card.
     */
    public List<Object[]> mrrByMonth() {
        return jdbc.query(
                MONTHS_CTE
                        + "SELECT m.ym, COALESCE(SUM(pc.price_kobo), 0) AS mrr_kobo "
                        + "FROM months m "
                        + "LEFT JOIN tenants t "
                        + "  ON t.created_at < m.month_start + interval '1 month' "
                        + " AND t.is_active = true "
                        + "LEFT JOIN pricing_config pc "
                        + "  ON pc.plan_name = t.subscription_plan "
                        + " AND pc.period = 'MONTHLY' "
                        + " AND pc.is_active = true "
                        + "GROUP BY m.ym, m.month_start "
                        + "ORDER BY m.month_start",
                (rs, n) -> new Object[]{rs.getString("ym"), rs.getLong("mrr_kobo")});
    }

    /** Current snapshot of tenants grouped by plan → {@code [plan, count]}, largest first. */
    public List<Object[]> planDistribution() {
        return jdbc.query(
                "SELECT subscription_plan, COUNT(*) AS cnt "
                        + "FROM tenants GROUP BY subscription_plan ORDER BY cnt DESC",
                (rs, n) -> new Object[]{rs.getString("subscription_plan"), rs.getLong("cnt")});
    }

    /** Current snapshot of subscriptions grouped by lifecycle status → {@code [status, count]}. */
    public List<Object[]> subscriptionStatusDistribution() {
        return jdbc.query(
                "SELECT status, COUNT(*) AS cnt "
                        + "FROM tenant_subscriptions GROUP BY status ORDER BY cnt DESC",
                (rs, n) -> new Object[]{rs.getString("status"), rs.getLong("cnt")});
    }

    /** Lifetime collected revenue: Σ amount_kobo of every PAID invoice across all tenants. */
    public long totalPaidRevenueKobo() {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_kobo), 0) FROM invoices WHERE status = 'PAID'",
                Long.class);
        return value != null ? value : 0L;
    }
}
