package com.admtechhub.maestrohr.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<AttendanceRecord, UUID> {

    @Query("SELECT a FROM AttendanceRecord a WHERE a.employee.id = :employeeId AND a.attendanceDate = :date AND a.tenant.id = :tenantId")
    Optional<AttendanceRecord> findByEmployeeIdAndAttendanceDate(@Param("employeeId") UUID employeeId, @Param("date") LocalDate date, @Param("tenantId") UUID tenantId);

    // True when the employee has any attendance record on file. Backs the hard-delete guard.
    @Query("SELECT COUNT(a) > 0 FROM AttendanceRecord a WHERE a.employee.id = :employeeId AND a.tenant.id = :tenantId")
    boolean existsByEmployeeId(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    @Query("SELECT a FROM AttendanceRecord a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.tenant.id = :tenantId")
    List<AttendanceRecord> findByEmployeeIdAndAttendanceDateBetween(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("tenantId") UUID tenantId);

    List<AttendanceRecord> findByAttendanceDate(LocalDate date);

    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.status = 'PRESENT' AND a.tenant.id = :tenantId")
    long countPresentDays(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("tenantId") UUID tenantId);

    @Query("SELECT a FROM AttendanceRecord a WHERE a.attendanceDate = :date AND a.status = 'ABSENT'")
    List<AttendanceRecord> findAbsenteesOnDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.status = 'ABSENT' AND a.tenant.id = :tenantId")
    long countAbsentDays(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("tenantId") UUID tenantId);

    // LATE records carry no automatic deduction — count is surfaced for HR review only.
    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.status = 'LATE' AND a.tenant.id = :tenantId")
    long countLateDays(@Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, @Param("tenantId") UUID tenantId);

    // Backs the redesigned attendance "Today" list (server-rendered fragment, Option 3):
    // one day's records with an optional status filter and free-text employee search.
    // Tenant-scoped on a.tenant.id (the entity holds tenant directly, unlike LeaveRequest
    // which reaches it via employee.tenant). Both filters are optional and null-safe:
    //   - :status null  → all statuses (the "All" chip);
    //   - :search null  → no text filter. CAST(:search AS string) makes Hibernate emit
    //     cast(? as varchar) so Postgres gets a concrete type for a null :search instead
    //     of inferring bytea ("function lower(bytea) does not exist"); the search is
    //     bypassed entirely when null. Matches employee name, employee number, or notes.
    //     Ordered by employee name so the daily roster reads alphabetically.
    @Query("SELECT a FROM AttendanceRecord a WHERE a.tenant.id = :tenantId " +
            "AND a.attendanceDate = :date " +
            "AND (:status IS NULL OR a.status = :status) " +
            "AND (:search IS NULL " +
            "  OR LOWER(CONCAT(a.employee.firstName, ' ', a.employee.lastName)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
            "  OR LOWER(a.employee.employeeNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
            "  OR LOWER(a.notes) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
            "ORDER BY a.employee.firstName ASC, a.employee.lastName ASC")
    List<AttendanceRecord> findFilteredByDate(@Param("tenantId") UUID tenantId,
                                              @Param("date") LocalDate date,
                                              @Param("status") AttendanceStatus status,
                                              @Param("search") String search);

    // Backs the Monthly Calendar fragment (Step B): one employee's records for a month,
    // tenant-scoped explicitly (mirroring findFilteredByDate) so the calendar never leaks
    // across tenants even if the @SQLRestriction session variable is unset. The service
    // maps these into a date→status grid and the present-rate summary.
    @Query("SELECT a FROM AttendanceRecord a WHERE a.tenant.id = :tenantId " +
            "AND a.employee.id = :employeeId " +
            "AND a.attendanceDate BETWEEN :startDate AND :endDate")
    List<AttendanceRecord> findForEmployeeMonth(@Param("tenantId") UUID tenantId,
                                                @Param("employeeId") UUID employeeId,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    // Per-status counts for one day across the tenant, backing the summary stat cards
    // and the filter-chip counts (so they reflect the full day's roster, not the filtered
    // view). Returns rows of [AttendanceStatus, Long]; statuses with no records are absent.
    @Query("SELECT a.status, COUNT(a) FROM AttendanceRecord a " +
            "WHERE a.tenant.id = :tenantId AND a.attendanceDate = :date GROUP BY a.status")
    List<Object[]> countByStatusForDate(@Param("tenantId") UUID tenantId,
                                        @Param("date") LocalDate date);

    // Per-status counts for one day within a single department, backing the DEPT_MANAGER
    // dashboard attendance snapshot. Same shape as countByStatusForDate but additionally
    // filtered to the manager's department. Returns rows of [AttendanceStatus, Long];
    // statuses with no records are absent.
    @Query("SELECT a.status, COUNT(a) FROM AttendanceRecord a " +
            "WHERE a.tenant.id = :tenantId AND a.employee.department.id = :departmentId " +
            "AND a.attendanceDate = :date GROUP BY a.status")
    List<Object[]> countByStatusForDateAndDepartment(@Param("tenantId") UUID tenantId,
                                                     @Param("departmentId") UUID departmentId,
                                                     @Param("date") LocalDate date);

    /**
     * Count ABSENT days per employee for a batch of employee IDs within a date range.
     * Single query replaces N individual countAbsentDays calls.
     */
    @Query("SELECT a.employee.id, COUNT(a) FROM AttendanceRecord a " +
            "WHERE a.employee.id IN :employeeIds " +
            "AND a.status = 'ABSENT' " +
            "AND a.attendanceDate >= :periodStart " +
            "AND a.attendanceDate <= :periodEnd " +
            "GROUP BY a.employee.id")
    List<Object[]> countAbsentDaysBatch(@Param("employeeIds") List<UUID> employeeIds,
                                        @Param("periodStart") LocalDate periodStart,
                                        @Param("periodEnd") LocalDate periodEnd);

    /**
     * Count LATE days per employee for a batch of employee IDs within a date range.
     */
    @Query("SELECT a.employee.id, COUNT(a) FROM AttendanceRecord a " +
            "WHERE a.employee.id IN :employeeIds " +
            "AND a.status = 'LATE' " +
            "AND a.attendanceDate >= :periodStart " +
            "AND a.attendanceDate <= :periodEnd " +
            "GROUP BY a.employee.id")
    List<Object[]> countLateDaysBatch(@Param("employeeIds") List<UUID> employeeIds,
                                      @Param("periodStart") LocalDate periodStart,
                                      @Param("periodEnd") LocalDate periodEnd);
}