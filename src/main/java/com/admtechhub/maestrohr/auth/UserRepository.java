package com.admtechhub.maestrohr.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    // Tenant-scoped lookup: the same email can exist as a User in a different tenant,
    // so callers that link by email (e.g. attaching an Employee to an existing login)
    // must confine the match to the current tenant.
    Optional<User> findByEmailAndTenantId(String email, UUID tenantId);
    List<User> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /**
     * Active users holding a given role within a tenant — used to fan notifications out to
     * HR (e.g. document-expiry alerts). Tenant-scoped so one tenant's HR never hears about
     * another's. Returns emails only (the in-app notification recipient key).
     */
    @Query("SELECT u.email FROM User u WHERE u.tenantId = :tenantId AND u.role = :role AND u.isActive = true")
    List<String> findActiveEmailsByTenantIdAndRole(UUID tenantId, UserRole role);

    @Query("SELECT COUNT(u) FROM User u WHERE u.lockedUntil IS NOT NULL")
    long countByLockedUntilIsNotNull();

    @Query("SELECT COUNT(u) FROM User u WHERE u.lockedUntil > CURRENT_TIMESTAMP")
    long countLockedUsers();

    @Modifying
    @Query("UPDATE User u SET u.hasCompletedOnboarding = true WHERE u.email = :email")
    void markOnboardingComplete(@Param("email") String email);
}
