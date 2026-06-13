package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.platform.SubscriptionSweepQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Daily sweep that lapses subscriptions whose paid period has ended: any tenant whose
 * subscription is {@code ACTIVE} with {@code currentPeriodEnd} in the past is moved to
 * {@code EXPIRED}, after which {@code LapsedAccessFilter} makes the account read-only
 * until the tenant pays to reactivate.
 *
 * <p>Multi-tenant safety: candidate tenant IDs come from the privileged datasource that
 * sees every tenant's rows (the scheduler has no tenant session). The loop then binds
 * {@link TenantContext} <em>per tenant</em> and clears it in a {@code finally} for each
 * iteration — never once for the whole job — so one tenant's context can never leak into
 * another's processing, and a failure on one tenant does not abort the rest.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionLapseJob {

    private final SubscriptionSweepQueries subscriptionSweepQueries;
    private final SubscriptionService subscriptionService;

    /** Runs at 01:00 every day (server time). */
    @Scheduled(cron = "0 0 1 * * *")
    public void lapseExpiredSubscriptions() {
        // Privileged cross-tenant scan: the scheduler has no tenant session, so under the
        // RLS-enforced primary role the scoped query would return nothing. The candidate scan
        // therefore goes through the privileged datasource; each candidate is then processed
        // under its own bound TenantContext below.
        List<UUID> candidates = subscriptionSweepQueries.findExpiredActiveTenantIds();
        if (candidates.isEmpty()) {
            log.debug("Subscription lapse sweep: no expired-active subscriptions");
            return;
        }

        log.info("Subscription lapse sweep: {} candidate tenant(s)", candidates.size());
        int lapsed = 0;
        for (UUID tenantId : candidates) {
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                if (subscriptionService.lapseExpired(tenantId)) {
                    lapsed++;
                }
            } catch (Exception e) {
                // One tenant's failure must not stop the sweep for the others.
                log.error("Subscription lapse failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Subscription lapse sweep complete: {} of {} candidate(s) lapsed",
                lapsed, candidates.size());
    }
}
