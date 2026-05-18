package com.admtechhub.maestrohr.performance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewCycleRepository extends JpaRepository<ReviewCycle, UUID> {

    @Query("SELECT c FROM ReviewCycle c WHERE c.tenant.id = :tenantId ORDER BY c.createdAt DESC")
    Page<ReviewCycle> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT c FROM ReviewCycle c WHERE c.tenant.id = :tenantId AND c.status = :status")
    List<ReviewCycle> findByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);

    @Query("SELECT c FROM ReviewCycle c WHERE c.employee.id = :employeeId")
    List<ReviewCycle> findByEmployeeId(@Param("employeeId") UUID employeeId);

    @Query("SELECT c FROM ReviewCycle c WHERE c.reviewer.id = :reviewerId")
    List<ReviewCycle> findByReviewerId(@Param("reviewerId") UUID reviewerId);

    @Query("SELECT COUNT(c) FROM ReviewCycle c WHERE c.tenant.id = :tenantId AND c.status = 'PENDING'")
    long countPendingByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(c) FROM ReviewCycle c WHERE c.tenant.id = :tenantId AND c.status = 'COMPLETED'")
    long countCompletedByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(c) FROM ReviewCycle c WHERE c.tenant.id = :tenantId AND c.dueDate < CURRENT_DATE AND c.status != 'COMPLETED'")
    long countOverdueByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT AVG(c.overallRating) FROM ReviewCycle c WHERE c.tenant.id = :tenantId AND c.status = 'COMPLETED'")
    Double getAverageRatingByTenantId(@Param("tenantId") UUID tenantId);
}