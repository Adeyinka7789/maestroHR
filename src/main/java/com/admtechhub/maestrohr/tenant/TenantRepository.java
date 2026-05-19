package com.admtechhub.maestrohr.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByRcNumber(String rcNumber);
    boolean existsByRcNumber(String rcNumber);
    List<Tenant> findAllByOrderByCreatedAtDesc();

    @Query("SELECT COUNT(t) FROM Tenant t WHERE t.isActive = true")
    long countActiveTenants();

    @Query("SELECT new com.admtechhub.maestrohr.tenant.TenantWithUserCountDTO(" +
            "t.id, t.companyName, t.rcNumber, t.industry, t.companySize, " +
            "t.subscriptionPlan, t.subscriptionExpiresAt, t.isActive, COUNT(u.id)) " +
            "FROM Tenant t LEFT JOIN User u ON u.tenantId = t.id " +
            "GROUP BY t.id, t.companyName, t.rcNumber, t.industry, t.companySize, " +
            "t.subscriptionPlan, t.subscriptionExpiresAt, t.isActive " +
            "ORDER BY t.createdAt DESC")
    List<TenantWithUserCountDTO> findAllWithUserCount();

    // New methods for webhook handling
    Optional<Tenant> findByPaystackCustomerCode(String paystackCustomerCode);
    Optional<Tenant> findByPaystackSubscriptionCode(String paystackSubscriptionCode);
}
