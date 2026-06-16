package com.admtechhub.maestrohr.employee;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    @Query("SELECT d FROM Department d WHERE d.tenant.id = :tenantId")
    List<Department> findAllByTenantId(@Param("tenantId") UUID tenantId);

    boolean existsByNameAndTenantId(String name, UUID tenantId);

    boolean existsByNameAndTenantIdAndIdNot(String name, UUID tenantId, UUID id);

    // ADD THIS METHOD - count departments by tenant
    @Query("SELECT COUNT(d) FROM Department d WHERE d.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT new com.admtechhub.maestrohr.employee.DepartmentDTO(d.id, d.name, d.createdAt, COUNT(e.id)) " +
            "FROM Department d LEFT JOIN Employee e ON e.department.id = d.id " +
            "WHERE d.tenant.id = :tenantId " +
            "GROUP BY d.id, d.name, d.createdAt")
    List<DepartmentDTO> findAllWithEmployeeCountByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT d FROM Department d WHERE d.tenant.id = :tenantId " +
            "AND LOWER(d.name) LIKE LOWER(CONCAT('%', :term, '%')) " +
            "ORDER BY d.name")
    List<Department> searchDepartments(@Param("tenantId") UUID tenantId,
                                       @Param("term") String term,
                                       Pageable pageable);

    // Backs the redesigned departments list (server-rendered fragment).
    // Reuses the employee-count LEFT JOIN/GROUP BY from findAllWithEmployeeCountByTenantId,
    // adding an optional name search. Null-safe: CAST(:search AS string) makes Hibernate emit
    // cast(? as varchar) so Postgres gets a concrete type for a null :search instead of inferring
    // bytea (which triggers "function lower(bytea) does not exist"); search is bypassed when null.
    @Query("SELECT new com.admtechhub.maestrohr.employee.DepartmentDTO(d.id, d.name, d.createdAt, COUNT(e.id)) " +
            "FROM Department d LEFT JOIN Employee e ON e.department.id = d.id " +
            "WHERE d.tenant.id = :tenantId " +
            "AND (:search IS NULL OR LOWER(d.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
            "GROUP BY d.id, d.name, d.createdAt " +
            "ORDER BY LOWER(d.name)")
    List<DepartmentDTO> findFilteredWithEmployeeCount(@Param("tenantId") UUID tenantId,
                                                      @Param("search") String search);
}