package com.admtechhub.maestrohr.gl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GlAccountConfigRepository extends JpaRepository<GlAccountConfig, UUID> {

    /** The tenant's single config row (tenant isolation via @SQLRestriction). */
    Optional<GlAccountConfig> findFirstBy();
}
