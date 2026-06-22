package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SUPERADMIN Feature 2 — privileged cross-tenant billing reads for the platform billing console.
 *
 * <p>Like {@link AdminStatsQueries}, every query here spans <em>all</em> tenants and therefore goes
 * through the privileged ({@code postgres}) datasource: under the RLS-enforced {@code maestro_app}
 * primary the V25 tenant-isolation policies would collapse these to the admin's own tenant (or
 * nothing). Reads only — the billing writes live in {@link AdminBillingWrites}.
 *
 * <p>The card figures (active / trialing / past-due counts) are read from the {@code status} column
 * of {@code tenant_subscriptions}. The MRR figures are computed in {@code AdminBillingService} from
 * the {@code pricing_config} single source of truth over <em>active tenants</em>, matching the
 * super-admin dashboard's MRR convention so the two totals reconcile — hence {@link #findActivePlanCounts}
 * groups the {@code tenants} table (by {@code is_active}), not {@code tenant_subscriptions}.
 */
@Repository
public class AdminBillingQueries {

    private final JdbcTemplate jdbc;

    public AdminBillingQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every tenant's subscription joined to its tenant, newest first — the full billing table. */
    public List<SubscriptionListRow> findAllSubscriptions() {
        return jdbc.query(
                "SELECT t.id AS tenant_id, t.company_name, t.is_active AS tenant_active, "
                        + "s.plan, s.period, s.status, s.current_period_end, s.price_kobo "
                        + "FROM tenant_subscriptions s "
                        + "JOIN tenants t ON t.id = s.tenant_id "
                        + "ORDER BY t.created_at DESC",
                (rs, n) -> new SubscriptionListRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("company_name"),
                        rs.getBoolean("tenant_active"),
                        rs.getString("plan"),
                        rs.getString("period"),
                        rs.getString("status"),
                        rs.getObject("current_period_end", OffsetDateTime.class),
                        rs.getLong("price_kobo")));
    }

    /** Tenants whose subscription has lapsed — {@code PAST_DUE} or {@code EXPIRED} — soonest-expired first. */
    public List<PastDueRow> findPastDueTenants() {
        return jdbc.query(
                "SELECT t.id AS tenant_id, t.company_name, s.plan, s.status, s.current_period_end "
                        + "FROM tenant_subscriptions s "
                        + "JOIN tenants t ON t.id = s.tenant_id "
                        + "WHERE s.status IN ('PAST_DUE', 'EXPIRED') "
                        + "ORDER BY s.current_period_end ASC NULLS LAST",
                (rs, n) -> new PastDueRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("company_name"),
                        rs.getString("plan"),
                        rs.getString("status"),
                        rs.getObject("current_period_end", OffsetDateTime.class)));
    }

    /**
     * Active-tenant counts grouped by plan, for the MRR-by-plan breakdown. Grouped by
     * {@code tenants.subscription_plan} / {@code is_active} so the derived MRR reconciles with the
     * super-admin dashboard card (which sums the {@code pricing_config} MONTHLY price of every active
     * tenant). FREE_TRIAL and any zero-priced plan still appear with their count; revenue is computed
     * downstream.
     */
    public List<PlanCountRow> findActivePlanCounts() {
        return jdbc.query(
                "SELECT subscription_plan, COUNT(*) AS tenant_count "
                        + "FROM tenants WHERE is_active = true "
                        + "GROUP BY subscription_plan",
                (rs, n) -> new PlanCountRow(
                        rs.getString("subscription_plan"),
                        rs.getLong("tenant_count")));
    }

    /** Subscription counts grouped by lifecycle status, for the summary cards (active / trialing / past-due). */
    public List<StatusCountRow> findStatusCounts() {
        return jdbc.query(
                "SELECT status, COUNT(*) AS status_count "
                        + "FROM tenant_subscriptions GROUP BY status",
                (rs, n) -> new StatusCountRow(
                        rs.getString("status"),
                        rs.getLong("status_count")));
    }

    /** Most-recent invoices across every tenant, for the platform-wide billing activity feed. */
    public List<PlatformInvoiceRow> findRecentInvoices(int limit) {
        return jdbc.query(
                "SELECT i.id, i.tenant_id, t.company_name, i.amount_kobo, i.status, i.plan, "
                        + "i.period, i.paid_at, i.created_at "
                        + "FROM invoices i JOIN tenants t ON t.id = i.tenant_id "
                        + "ORDER BY i.created_at DESC LIMIT ?",
                (rs, n) -> new PlatformInvoiceRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("company_name"),
                        rs.getLong("amount_kobo"),
                        rs.getString("status"),
                        rs.getString("plan"),
                        rs.getString("period"),
                        rs.getObject("paid_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)),
                limit);
    }

    /** One row of the full subscriptions table — tenant identity plus its subscription state. */
    public record SubscriptionListRow(
            UUID tenantId,
            String companyName,
            boolean tenantActive,
            String plan,
            String period,
            String status,
            OffsetDateTime currentPeriodEnd,
            long priceKobo) {
    }

    /** One row of the past-due tenants table. */
    public record PastDueRow(
            UUID tenantId,
            String companyName,
            String plan,
            String status,
            OffsetDateTime currentPeriodEnd) {
    }

    /** Active-tenant count for a single plan. */
    public record PlanCountRow(String plan, long count) {
    }

    /** Subscription count for a single lifecycle status. */
    public record StatusCountRow(String status, long count) {
    }

    /** One row of the platform-wide recent-invoices feed (carries the owning tenant's name). */
    public record PlatformInvoiceRow(
            UUID id,
            UUID tenantId,
            String companyName,
            long amountKobo,
            String status,
            String plan,
            String period,
            OffsetDateTime paidAt,
            OffsetDateTime createdAt) {
    }
}
