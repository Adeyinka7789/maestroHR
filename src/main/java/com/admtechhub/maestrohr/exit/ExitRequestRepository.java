package com.admtechhub.maestrohr.exit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ExitRequestRepository extends JpaRepository<ExitRequest, UUID> {

    @Query("SELECT e FROM ExitRequest e WHERE e.tenant.id = :tenantId ORDER BY e.createdAt DESC")
    List<ExitRequest> findByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT e FROM ExitRequest e WHERE e.employee.id = :employeeId")
    List<ExitRequest> findByEmployeeId(@Param("employeeId") UUID employeeId);

    @Query("SELECT COUNT(e) FROM ExitRequest e WHERE e.tenant.id = :tenantId AND e.status = 'PENDING'")
    long countPendingByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(e) FROM ExitRequest e WHERE e.tenant.id = :tenantId AND e.status = 'IN_CLEARANCE'")
    long countInClearanceByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(e) FROM ExitRequest e WHERE e.tenant.id = :tenantId AND e.status = 'COMPLETED'")
    long countCompletedByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COALESCE(SUM(f.totalPayable), 0) FROM FinalSettlement f WHERE f.exitRequest.tenant.id = :tenantId AND f.paymentStatus = 'PENDING'")
    BigDecimal getTotalPendingPayable(@Param("tenantId") UUID tenantId);
}