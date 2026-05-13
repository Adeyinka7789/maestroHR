package com.admtechhub.maestrohr.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayGradeRepository extends JpaRepository<PayGrade, UUID> {
    List<PayGrade> findAllByTenantIdAndIsActive(UUID tenantId, boolean isActive);
    boolean existsByNameAndTenantId(String name, UUID tenantId);
}