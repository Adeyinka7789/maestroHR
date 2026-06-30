package com.admtechhub.maestrohr.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    // Soft-delete guards: true when any LIVE employee (deleted_at IS NULL, enforced by the
    // entity @SQLRestriction) is still assigned to the department / pay grade. Used to block
    // soft-deleting a department or grade out from under employees that still reference it.
    boolean existsByDepartmentId(UUID departmentId);

    boolean existsByPayGradeId(UUID payGradeId);

    Page<Employee> findByStatus(EmployeeStatus status, Pageable pageable);

    // Search by name (case in-sensitive)
    @Query("SELECT e FROM Employee e WHERE LOWER(e.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(e.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(e.employeeNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Employee> searchEmployees(@Param("searchTerm") String searchTerm, Pageable pageable);

    // Count active employees
    long countByStatus(EmployeeStatus status);

    // Check if employee number exists for this tenant
    @Query("SELECT COUNT(e) > 0 FROM Employee e WHERE e.employeeNumber = :employeeNumber AND e.tenant.id = :tenantId")
    boolean existsByEmployeeNumber(@Param("employeeNumber") String employeeNumber, @Param("tenantId") UUID tenantId);

    // Check if email exists for this tenant
    @Query("SELECT COUNT(e) > 0 FROM Employee e WHERE e.email = :email AND e.tenant.id = :tenantId")
    boolean existsByEmail(@Param("email") String email, @Param("tenantId") UUID tenantId);

    // Add these methods to EmployeeRepository interface

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.tenant.id = :tenantId AND e.status = :status")
    long countByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") EmployeeStatus status);

    @Query(value = "SELECT e FROM Employee e LEFT JOIN FETCH e.department WHERE e.tenant.id = :tenantId",
           countQuery = "SELECT COUNT(e) FROM Employee e WHERE e.tenant.id = :tenantId")
    Page<Employee> findAllByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.tenant.id = :tenantId AND e.id = :id")
    Optional<Employee> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    // Combined filter query for the employees list page.
    // Null-safe: CAST(:search AS string) makes Hibernate emit cast(? as varchar), so Postgres
    // gets a concrete type for a null :search instead of inferring bytea
    // (which triggered "function lower(bytea) does not exist"); each filter is bypassed when its param is null.
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

    // For payroll computation: active employees plus anyone terminated within the period
    // (so mid-month leavers receive a prorated final pay rather than nothing).
    // Tenant scoping is automatic via @SQLRestriction on Employee.
    @Query("SELECT e FROM Employee e WHERE e.status = :active " +
           "OR (e.status = :terminated AND e.terminationDate >= :periodStart AND e.terminationDate <= :periodEnd)")
    List<Employee> findActiveOrTerminatedDuringPeriod(
            @Param("active") EmployeeStatus active,
            @Param("terminated") EmployeeStatus terminated,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);
}