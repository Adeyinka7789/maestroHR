package com.admtechhub.maestrohr.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    // Basic queries (tenant isolation via @SQLRestriction on entity)
    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    Optional<Employee> findByEmail(String email);

    List<Employee> findByStatus(EmployeeStatus status);

    List<Employee> findByDepartmentId(UUID departmentId);

    List<Employee> findByPayGradeId(UUID payGradeId);

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

    @Query("SELECT e FROM Employee e WHERE e.tenant.id = :tenantId")
    Page<Employee> findAllByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.tenant.id = :tenantId AND e.id = :id")
    Optional<Employee> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}