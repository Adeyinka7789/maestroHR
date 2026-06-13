package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Phase E4b — privileged candidate scan for the daily subscription-lapse sweep (op 6).
 *
 * <p>{@code SubscriptionLapseJob} runs with no tenant session and must see every tenant's
 * subscription row to find the expired-active ones. The existing native query bypasses
 * {@code @SQLRestriction} but not RLS; under {@code maestro_app} it would return nothing.
 * Routed through the privileged datasource it sees all tenants. The caller still binds each
 * candidate tenant's session individually before mutating its subscription.
 *
 * <p>Read only; not yet wired into {@code SubscriptionLapseJob} (that is E4c).
 */
@Repository
public class SubscriptionSweepQueries {

    private final JdbcTemplate jdbc;

    public SubscriptionSweepQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tenant IDs whose ACTIVE subscription period has elapsed — the lapse candidates. */
    public List<UUID> findExpiredActiveTenantIds() {
        return jdbc.query(
                "SELECT tenant_id FROM tenant_subscriptions "
                        + "WHERE status = 'ACTIVE' AND current_period_end < now()",
                (rs, n) -> rs.getObject("tenant_id", UUID.class));
    }
}
