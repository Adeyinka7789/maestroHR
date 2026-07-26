package com.admtechhub.maestrohr.overtime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OvertimePolicyRepository extends JpaRepository<OvertimePolicy, UUID> {

    /** The tenant's single active policy (tenant isolation + soft-delete via @SQLRestriction). */
    Optional<OvertimePolicy> findFirstByActiveTrue();

    long count();
}
