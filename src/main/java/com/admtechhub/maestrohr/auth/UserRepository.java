package com.admtechhub.maestrohr.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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

    @Query("SELECT COUNT(u) FROM User u WHERE u.lockedUntil IS NOT NULL")
    long countByLockedUntilIsNotNull();

    @Query("SELECT COUNT(u) FROM User u WHERE u.lockedUntil > CURRENT_TIMESTAMP")
    long countLockedUsers();
}
