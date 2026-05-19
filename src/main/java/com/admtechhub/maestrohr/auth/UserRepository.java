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
    List<User> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.lockedUntil IS NOT NULL")
    long countByLockedUntilIsNotNull();

    @Query("SELECT COUNT(u) FROM User u WHERE u.lockedUntil > CURRENT_TIMESTAMP")
    long countLockedUsers();
}
