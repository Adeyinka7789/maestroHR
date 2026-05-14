package com.admtechhub.maestrohr.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByRcNumber(String rcNumber);
    boolean existsByRcNumber(String rcNumber);
    List<Tenant> findAllByOrderByCreatedAtDesc();

    // New methods for webhook handling
    Optional<Tenant> findByPaystackCustomerCode(String paystackCustomerCode);
    Optional<Tenant> findByPaystackSubscriptionCode(String paystackSubscriptionCode);
}
