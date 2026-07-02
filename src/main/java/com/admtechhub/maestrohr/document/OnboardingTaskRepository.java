package com.admtechhub.maestrohr.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OnboardingTaskRepository extends JpaRepository<OnboardingTask, UUID> {

    /** The ordered checklist for one employee. */
    @Query("SELECT t FROM OnboardingTask t WHERE t.employeeId = :employeeId AND t.tenantId = :tenantId ORDER BY t.taskOrder ASC")
    List<OnboardingTask> findByEmployeeIdOrderByTaskOrderAsc(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    /** True once at least one task exists for the employee — guards idempotent seeding. */
    @Query("SELECT COUNT(t) > 0 FROM OnboardingTask t WHERE t.employeeId = :employeeId AND t.tenantId = :tenantId")
    boolean existsByEmployeeId(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    /** Outstanding (unticked) tasks — used to decide whether the checklist still shows. */
    @Query("SELECT COUNT(t) FROM OnboardingTask t WHERE t.employeeId = :employeeId AND t.completed = false AND t.tenantId = :tenantId")
    long countByEmployeeIdAndCompletedFalse(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);
}