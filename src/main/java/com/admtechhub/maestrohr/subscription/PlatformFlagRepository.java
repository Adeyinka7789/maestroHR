package com.admtechhub.maestrohr.subscription;
import com.admtechhub.maestrohr.flags.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-global — no tenant scoping. The {@code maestro_app} role reads/writes
 * {@code platform_flags} freely (no RLS policy is attached, per V30).
 */
@Repository
public interface PlatformFlagRepository extends JpaRepository<PlatformFlag, UUID> {

    Optional<PlatformFlag> findByName(String name);

    List<PlatformFlag> findAllByOrderByNameAsc();
}
