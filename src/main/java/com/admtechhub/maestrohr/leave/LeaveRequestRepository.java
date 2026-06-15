package com.admtechhub.maestrohr.leave;

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
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    List<LeaveRequest> findByEmployeeId(UUID employeeId);

    List<LeaveRequest> findByEmployeeIdAndStatus(UUID employeeId, LeaveStatus status);

    List<LeaveRequest> findByStatus(LeaveStatus status);

    List<LeaveRequest> findByStatusAndStartDateBetween(LeaveStatus status, LocalDate startDate, LocalDate endDate);

    Page<LeaveRequest> findByStatus(LeaveStatus status, Pageable pageable);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.id = :employeeId " +
            "AND l.status = 'APPROVED' " +
            "AND l.startDate <= :endDate AND l.endDate >= :startDate")
    List<LeaveRequest> findApprovedLeavesInDateRange(@Param("employeeId") UUID employeeId,
                                                     @Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.tenant.id = :tenantId " +
            "AND (LOWER(CONCAT(l.employee.firstName, ' ', l.employee.lastName)) LIKE LOWER(CONCAT('%', :term, '%')) " +
            "OR LOWER(l.leaveType.name) LIKE LOWER(CONCAT('%', :term, '%')) " +
            "OR LOWER(l.status) LIKE LOWER(CONCAT('%', :term, '%')) " +
            "OR LOWER(l.reason) LIKE LOWER(CONCAT('%', :term, '%'))) " +
            "ORDER BY l.createdAt DESC")
    List<LeaveRequest> searchLeaveRequests(@Param("tenantId") UUID tenantId,
                                           @Param("term") String term,
                                           Pageable pageable);

    @Query("SELECT COALESCE(SUM(l.daysRequested), 0) FROM LeaveRequest l " +
            "WHERE l.employee.id = :employeeId " +
            "AND l.leaveType.id = :leaveTypeId " +
            "AND l.status = 'APPROVED' " +
            "AND YEAR(l.startDate) = :year")
    Integer getTotalDaysTakenInYear(@Param("employeeId") UUID employeeId,
                                    @Param("leaveTypeId") UUID leaveTypeId,
                                    @Param("year") Integer year);

    // Add these methods to LeaveRequestRepository interface

    @Query("SELECT COUNT(l) FROM LeaveRequest l WHERE l.employee.tenant.id = :tenantId AND l.status = :status")
    long countByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") LeaveStatus status);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.tenant.id = :tenantId AND l.status = :status")
    List<LeaveRequest> findByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") LeaveStatus status);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.tenant.id = :tenantId ORDER BY l.createdAt DESC")
    Page<LeaveRequest> findByEmployeeTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    // Returns approved unpaid leave requests that overlap the given payroll period.
    // Callers must clamp each result to the period boundaries before summing days.
    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.id = :employeeId " +
            "AND l.status = 'APPROVED' AND l.leaveType.isPaid = false " +
            "AND l.startDate <= :periodEnd AND l.endDate >= :periodStart")
    List<LeaveRequest> findApprovedUnpaidLeavesInRange(
            @Param("employeeId") UUID employeeId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);

    // Backs the redesigned leave list (server-rendered fragment, Option 3): the
    // approval-queue / history table with an optional status filter and free-text
    // search. Both parameters are optional and null-safe:
    //   - :status null  → all statuses (the "All" chip);
    //   - :search null  → no text filter. CAST(:search AS string) makes Hibernate emit
    //     cast(? as varchar) so Postgres gets a concrete type for a null :search instead
    //     of inferring bytea ("function lower(bytea) does not exist"); the search is
    //     bypassed entirely when null. Mirrors the existing searchLeaveRequests text
    //     match (employee name / leave-type name / reason). Newest first.
    @Query("SELECT l FROM LeaveRequest l WHERE l.employee.tenant.id = :tenantId " +
            "AND (:status IS NULL OR l.status = :status) " +
            "AND (:search IS NULL " +
            "  OR LOWER(CONCAT(l.employee.firstName, ' ', l.employee.lastName)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
            "  OR LOWER(l.leaveType.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
            "  OR LOWER(l.reason) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
            "ORDER BY l.createdAt DESC")
    List<LeaveRequest> findFiltered(@Param("tenantId") UUID tenantId,
                                    @Param("status") LeaveStatus status,
                                    @Param("search") String search);

    // Per-status counts for the whole tenant, backing the summary strip and the
    // filter-chip counts (so they reflect the full data set, not the filtered view).
    // Returns rows of [LeaveStatus, Long]; statuses with zero requests are absent.
    @Query("SELECT l.status, COUNT(l) FROM LeaveRequest l " +
            "WHERE l.employee.tenant.id = :tenantId GROUP BY l.status")
    List<Object[]> countByStatusForTenant(@Param("tenantId") UUID tenantId);
}
