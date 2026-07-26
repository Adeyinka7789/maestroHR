package com.admtechhub.maestrohr.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    // Basic queries (tenant isolation via @SQLRestriction on entity)
    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    Optional<Employee> findByEmail(String email);

    List<Employee> findByStatus(EmployeeStatus status);

    List<Employee> findByDepartmentId(UUID departmentId, PageRequest pageRequest);

    List<Employee> findByPayGradeId(UUID payGradeId);

    boolean existsByDepartmentId(UUID departmentId);

    boolean existsByPayGradeId(UUID payGradeId);

    boolean existsByShiftId(UUID shiftId);

    Page<Employee> findByStatus(EmployeeStatus status, Pageable pageable);

    // Search by name (case insensitive)
    @Query("SELECT e FROM Employee e WHERE LOWER(e.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(e.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(e.employeeNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Employee> searchEmployees(@Param("searchTerm") String searchTerm, Pageable pageable);

    long countByStatus(EmployeeStatus status);

    @Query("SELECT COUNT(e) > 0 FROM Employee e WHERE e.employeeNumber = :employeeNumber AND e.tenant.id = :tenantId")
    boolean existsByEmployeeNumber(@Param("employeeNumber") String employeeNumber, @Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(e) > 0 FROM Employee e WHERE e.email = :email AND e.tenant.id = :tenantId")
    boolean existsByEmail(@Param("email") String email, @Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.tenant.id = :tenantId AND e.status = :status")
    long countByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") EmployeeStatus status);

    // ================================================================
    // STEP 9: Fetch-optimized queries
    // ================================================================

    /**
     * Fetch a page of employees with department and payGrade eagerly loaded.
     * Uses @EntityGraph for database-native pagination (no HHH000104 warning).
     */
    @EntityGraph(attributePaths = {"department", "payGrade"})
    Page<Employee> findAllByTenantId(UUID tenantId, Pageable pageable);

    /**
     * Fetch a single employee with department, payGrade, and tenant eagerly loaded.
     * Uses JOIN FETCH (safe for single-entity lookup).
     */
    @Query("SELECT DISTINCT e FROM Employee e " +
            "LEFT JOIN FETCH e.department d " +
            "LEFT JOIN FETCH e.payGrade pg " +
            "LEFT JOIN FETCH e.tenant t " +
            "WHERE e.id = :id AND e.tenant.id = :tenantId")
    Optional<Employee> findByIdWithDetails(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT e FROM Employee e WHERE e.tenant.id = :tenantId AND e.id = :id")
    Optional<Employee> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    // Combined filter query for the employees list page
    @Query("SELECT e FROM Employee e WHERE e.tenant.id = :tenantId " +
            "AND (:search IS NULL OR " +
            "     LOWER(e.firstName)      LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
            "     LOWER(e.lastName)       LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
            "     LOWER(e.email)          LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
            "     LOWER(e.employeeNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
            "AND (:departmentId IS NULL OR e.department.id = :departmentId) " +
            "AND (:status IS NULL OR e.status = :status)")
    Page<Employee> findFiltered(@Param("tenantId") UUID tenantId,
                                @Param("search") String search,
                                @Param("departmentId") UUID departmentId,
                                @Param("status") EmployeeStatus status,
                                Pageable pageable);

    /**
     * Compliance dashboard — employees still on probation (unconfirmed, with a probation end
     * date) whose window closes on or before {@code through}, which by design also captures
     * anyone already overdue (probationEndDate &lt; today). Terminated staff are excluded.
     * Tenant isolation + soft-delete filtering come from the entity {@code @SQLRestriction};
     * the partial index from V62 backs this scan. Ordered soonest-first.
     */
    @Query("SELECT e FROM Employee e " +
            "WHERE e.confirmedAt IS NULL " +
            "AND e.probationEndDate IS NOT NULL " +
            "AND e.probationEndDate <= :through " +
            "AND e.status <> :terminated " +
            "ORDER BY e.probationEndDate ASC")
    List<Employee> findProbationDueThrough(@Param("through") LocalDate through,
                                           @Param("terminated") EmployeeStatus terminated);

    /** [costCenterId, count] for assigned employees — backs the cost-center manager's counts. */
    @Query("SELECT e.costCenter.id, COUNT(e) FROM Employee e WHERE e.costCenter.id IS NOT NULL GROUP BY e.costCenter.id")
    List<Object[]> countByCostCenter();

    /**
     * Compliance dashboard — employees on a fixed-term contract whose {@code contractEndDate}
     * falls on or before {@code through}, which also captures contracts that have already lapsed
     * (contractEndDate &lt; today). Terminated staff are excluded. Tenant isolation + soft-delete
     * filtering come from the entity {@code @SQLRestriction}; the partial index from V63 backs
     * this scan. Ordered soonest-first.
     */
    @Query("SELECT e FROM Employee e " +
            "WHERE e.contractEndDate IS NOT NULL " +
            "AND e.contractEndDate <= :through " +
            "AND e.status <> :terminated " +
            "ORDER BY e.contractEndDate ASC")
    List<Employee> findContractsEndingThrough(@Param("through") LocalDate through,
                                              @Param("terminated") EmployeeStatus terminated);

    /**
     * For payroll computation: active employees plus those terminated within the period.
     *
     * FETCH OPTIMIZED: Eagerly loads department and payGrade to prevent N+1 queries
     * when PayrollEngine accesses employee.getPayGrade() or employee.getDepartment()
     * for salary calculations, proration rules, and benefit formulas.
     */
    @Query("SELECT DISTINCT e FROM Employee e " +
            "LEFT JOIN FETCH e.department d " +
            "LEFT JOIN FETCH e.payGrade pg " +
            "WHERE e.status = :active " +
            "OR (e.status = :terminated AND e.terminationDate >= :periodStart AND e.terminationDate <= :periodEnd)")
    List<Employee> findActiveOrTerminatedDuringPeriod(
            @Param("active") EmployeeStatus active,
            @Param("terminated") EmployeeStatus terminated,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);
}