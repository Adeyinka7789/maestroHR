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
}
