package com.admtechhub.maestrohr.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayGradeRepository extends JpaRepository<PayGrade, UUID> {

    List<PayGrade> findAllByTenantIdAndIsActive(UUID tenantId, boolean isActive);

    boolean existsByNameAndTenantId(String name, UUID tenantId);

    @Query("SELECT p FROM PayGrade p WHERE p.tenant.id = :tenantId")
    List<PayGrade> findAllByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT p FROM PayGrade p WHERE p.tenant.id = :tenantId")
    Page<PayGrade> findAllByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);
}