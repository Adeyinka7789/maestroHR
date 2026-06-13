package com.admtechhub.maestrohr.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {

    /**
     * The single subscription row for a tenant. Note: subject to the entity's
     * {@code @SQLRestriction}, so the {@code app.current_tenant} session variable
     * must already be bound to this tenant for the row to be visible.
     */
    Optional<TenantSubscription> findByTenantId(UUID tenantId);

    /**
     * Tenant IDs whose ACTIVE subscription period has elapsed — the candidates the
     * daily lapse job flips to EXPIRED. Native, so it is NOT subject to the entity's
     * {@code @SQLRestriction}: the scheduler runs with no tenant session bound and must
     * see every tenant's row. The caller binds each tenant's session individually before
     * mutating its subscription.
     */
    @Query(value = "SELECT tenant_id FROM tenant_subscriptions "
            + "WHERE status = 'ACTIVE' AND current_period_end < now()",
            nativeQuery = true)
    List<UUID> findExpiredActiveTenantIds();
}
