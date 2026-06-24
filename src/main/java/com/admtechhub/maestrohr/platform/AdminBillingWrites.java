package com.admtechhub.maestrohr.platform;

import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SUPERADMIN Feature 2 — privileged cross-tenant billing writes (the write counterpart of
 * {@link AdminBillingQueries}). A SUPER_ADMIN carries their own tenant in session, so changing any
 * <em>other</em> tenant's subscription on the scoped JPA path would fail the RLS policy under
 * {@code maestro_app}; routed through the privileged ({@code postgres}) datasource these bypass RLS,
 * exactly as the suspend/reactivate writes in {@link TenantUserWrites} already do.
 *
 * <p>Each method dual-writes the two billing tables on a single explicit transaction:
 * {@code tenant_subscriptions} is authoritative, and the denormalized
 * {@code tenants.subscription_plan} / {@code subscription_expires_at} columns are kept in sync (the
 * same columns the admin create/update-tenant writes maintain). Every method returns {@code false}
 * when no subscription row exists for the tenant (not provisioned), mirroring suspend/reactivate.
 *
 * <p>Plan price comes from the {@code pricing_config} single source of truth via
 * {@link PricingService}; {@code pricing_config} is global (no {@code @SQLRestriction}) so the lookup
 * is safe under any session and is resolved before the privileged connection is opened.
 */
@Repository
public class AdminBillingWrites {

    private final JdbcTemplate jdbc;
    private final PricingService pricingService;

    public AdminBillingWrites(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc,
                              PricingService pricingService) {
        this.jdbc = jdbc;
        this.pricingService = pricingService;
    }

    /**
     * Change a tenant's plan/period (admin upgrade or downgrade — the two are the same operation,
     * only the tier direction differs). Snapshots the {@code pricing_config} price into
     * {@code price_kobo}, forces the subscription {@code ACTIVE}, and resets the period window to
     * {@code [now, now + period months]}. Syncs the denormalized {@code tenants} columns.
     *
     * @return false if the tenant has no subscription row.
     */
    public boolean changePlan(UUID tenantId, SubscriptionPlan plan, PaymentPeriod period) {
        long priceKobo = pricingService.getPrice(plan.name(), period.name());
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end = start.plusMonths(period.getMonths());

        Boolean result = jdbc.execute((Connection con) -> inTransaction(con, () -> {
            int rows;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE tenant_subscriptions SET plan = ?, period = ?, status = 'ACTIVE', "
                            + "price_kobo = ?, current_period_start = ?, current_period_end = ? "
                            + "WHERE tenant_id = ?")) {
                ps.setString(1, plan.name());
                ps.setString(2, period.name());
                ps.setLong(3, priceKobo);
                ps.setObject(4, start);
                ps.setObject(5, end);
                ps.setObject(6, tenantId);
                rows = ps.executeUpdate();
            }
            if (rows == 0) {
                return false;
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE tenants SET subscription_plan = ?, subscription_expires_at = ? WHERE id = ?")) {
                ps.setString(1, plan.name());
                ps.setObject(2, end);
                ps.setObject(3, tenantId);
                ps.executeUpdate();
            }
            return true;
        }));
        return Boolean.TRUE.equals(result);
    }

    /**
     * Extend a trial by {@code days}, keeping the subscription {@code TRIALING}. The new end is
     * {@code max(currentPeriodEnd, now) + days}, computed under a row lock so the synced
     * {@code tenants.subscription_expires_at} carries the exact same value.
     *
     * @return false if the tenant has no subscription row, or it is not currently {@code TRIALING}.
     */
    public boolean extendTrial(UUID tenantId, int days) {
        Boolean result = jdbc.execute((Connection con) -> inTransaction(con, () -> {
            OffsetDateTime currentEnd;
            String status;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT status, current_period_end FROM tenant_subscriptions "
                            + "WHERE tenant_id = ? FOR UPDATE")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    status = rs.getString("status");
                    currentEnd = rs.getObject("current_period_end", OffsetDateTime.class);
                }
            }
            if (!"TRIALING".equals(status)) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime base = (currentEnd != null && currentEnd.isAfter(now)) ? currentEnd : now;
            OffsetDateTime newEnd = base.plusDays(days);

            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE tenant_subscriptions SET current_period_end = ? WHERE tenant_id = ?")) {
                ps.setObject(1, newEnd);
                ps.setObject(2, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE tenants SET subscription_expires_at = ? WHERE id = ?")) {
                ps.setObject(1, newEnd);
                ps.setObject(2, tenantId);
                ps.executeUpdate();
            }
            return true;
        }));
        return Boolean.TRUE.equals(result);
    }

    /**
     * Manually flag a tenant's subscription {@code PAST_DUE} (admin correction). Touches only the
     * subscription status — the tenant row stays as-is, since past-due is not a suspension.
     *
     * @return false if the tenant has no subscription row.
     */
    public boolean markPastDue(UUID tenantId) {
        int rows = jdbc.update(
                "UPDATE tenant_subscriptions SET status = 'PAST_DUE' WHERE tenant_id = ?",
                tenantId);
        return rows > 0;
    }

    /** Run {@code work} inside one all-or-nothing transaction on {@code con}, restoring auto-commit. */
    private boolean inTransaction(Connection con, TxWork work) throws SQLException {
        boolean prev = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            boolean ok = work.run();
            if (ok) {
                con.commit();
            } else {
                con.rollback();
            }
            return ok;
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(prev);
        }
    }

    @FunctionalInterface
    private interface TxWork {
        boolean run() throws SQLException;
    }

    /**
     * Insert a new subscription row for a tenant that has none, or update the existing one.
     * This is a safe "upsert" that can be used both for fresh subscriptions and upgrades.
     */
    public boolean upsertSubscription(UUID tenantId, SubscriptionPlan plan, PaymentPeriod period) {
        long priceKobo = pricingService.getPrice(plan.name(), period.name());
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end = start.plusMonths(period.getMonths());

        Boolean result = jdbc.execute((Connection con) -> inTransaction(con, () -> {
            // Try update first
            int updated;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE tenant_subscriptions SET plan = ?, period = ?, status = 'ACTIVE', "
                            + "price_kobo = ?, current_period_start = ?, current_period_end = ? "
                            + "WHERE tenant_id = ?")) {
                ps.setString(1, plan.name());
                ps.setString(2, period.name());
                ps.setLong(3, priceKobo);
                ps.setObject(4, start);
                ps.setObject(5, end);
                ps.setObject(6, tenantId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                // Insert new row
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO tenant_subscriptions (tenant_id, plan, period, status, price_kobo, current_period_start, current_period_end) "
                                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, plan.name());
                    ps.setString(3, period.name());
                    ps.setLong(4, priceKobo);
                    ps.setObject(5, start);
                    ps.setObject(6, end);
                    ps.executeUpdate();
                }
            }
            // Sync denormalised columns
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE tenants SET subscription_plan = ?, subscription_expires_at = ? WHERE id = ?")) {
                ps.setString(1, plan.name());
                ps.setObject(2, end);
                ps.setObject(3, tenantId);
                ps.executeUpdate();
            }
            return true;
        }));
        return Boolean.TRUE.equals(result);
    }
}
