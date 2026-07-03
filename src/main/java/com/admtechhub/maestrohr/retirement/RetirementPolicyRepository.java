package com.admtechhub.maestrohr.retirement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RetirementPolicyRepository extends JpaRepository<RetirementPolicy, UUID> {

    /** The tenant's single retirement policy row (RLS-scoped), if one has been created. */
    Optional<RetirementPolicy> findByTenantId(UUID tenantId);
}
