package com.admtechhub.maestrohr.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    @Query("SELECT d FROM Department d WHERE d.tenant.id = :tenantId")
    List<Department> findAllByTenantId(@Param("tenantId") UUID tenantId);

    boolean existsByNameAndTenantId(String name, UUID tenantId);

    // ADD THIS METHOD - count departments by tenant
    @Query("SELECT COUNT(d) FROM Department d WHERE d.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);
}