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
public interface CertificationRepository extends JpaRepository<Certification, UUID> {

    @Query("SELECT c FROM Certification c WHERE c.tenant.id = :tenantId ORDER BY c.expiryDate ASC")
    Page<Certification> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT c FROM Certification c WHERE c.employee.id = :employeeId")
    List<Certification> findByEmployeeId(@Param("employeeId") UUID employeeId);

    @Query("SELECT c FROM Certification c WHERE c.expiryDate < CURRENT_DATE AND c.reminderSent = false AND c.status = 'ACTIVE'")
    List<Certification> findExpiringCertifications();
}