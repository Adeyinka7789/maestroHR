package com.admtechhub.maestrohr.performance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewTemplateRepository extends JpaRepository<ReviewTemplate, UUID> {

    @Query("SELECT t FROM ReviewTemplate t WHERE t.tenant.id = :tenantId AND t.status = 'ACTIVE'")
    List<ReviewTemplate> findActiveByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT t FROM ReviewTemplate t WHERE t.tenant.id = :tenantId")
    List<ReviewTemplate> findAllByTenantId(@Param("tenantId") UUID tenantId);
}