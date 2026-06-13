package com.admtechhub.maestrohr.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
