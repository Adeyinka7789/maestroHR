package com.admtechhub.maestrohr.training;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TrainingProgramRepository extends JpaRepository<TrainingProgram, UUID> {

    @Query("SELECT t FROM TrainingProgram t WHERE t.tenant.id = :tenantId ORDER BY t.createdAt DESC")
    Page<TrainingProgram> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT t FROM TrainingProgram t WHERE t.tenant.id = :tenantId AND t.status = 'ACTIVE'")
    List<TrainingProgram> findActiveByTenantId(@Param("tenantId") UUID tenantId);
}