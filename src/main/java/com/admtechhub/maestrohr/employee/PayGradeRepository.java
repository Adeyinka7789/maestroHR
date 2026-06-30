package com.admtechhub.maestrohr.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayGradeRepository extends JpaRepository<PayGrade, UUID> {

    List<PayGrade> findAllByTenantIdAndIsActive(UUID tenantId, boolean isActive);

    boolean existsByNameAndTenantId(String name, UUID tenantId);

    boolean existsByNameAndTenantIdAndIdNot(String name, UUID tenantId, UUID id);

    @Query("SELECT p FROM PayGrade p WHERE p.tenant.id = :tenantId")
    List<PayGrade> findAllByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT p FROM PayGrade p WHERE p.tenant.id = :tenantId")
    Page<PayGrade> findAllByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT p FROM PayGrade p WHERE p.tenant.id = :tenantId AND p.isActive = true " +
            "AND LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%')) " +
            "ORDER BY p.name")
    List<PayGrade> searchPayGrades(@Param("tenantId") UUID tenantId,
                                   @Param("term") String term,
                                   Pageable pageable);

    // Backs the redesigned pay-grades list (server-rendered fragment, Option 3).
    // Returns each active grade with its salary components plus the headcount assigned
    // to it (LEFT JOIN Employee on e.payGrade.id, so grades with zero employees still
    // appear) and an optional name search. Null-safe: CAST(:search AS string) makes
    // Hibernate emit cast(? as varchar) so Postgres gets a concrete type for a null
    // :search instead of inferring bytea (which triggers "function lower(bytea) does
    // not exist"); the search is bypassed entirely when :search is null. Ordered by
    // gross salary (basic + all allowances) descending to match the card layout, which
    // surfaces the highest band first. Mirrors
    // DepartmentRepository#findFilteredWithEmployeeCount.
    @Query("SELECT new com.admtechhub.maestrohr.employee.PayGradeListRow(" +
            "  p.id, p.name, p.basicSalary, p.housingAllowance, p.transportAllowance, " +
            "  p.otherAllowances, p.isActive, COUNT(e.id), p.loanPolicy.id) " +
            "FROM PayGrade p LEFT JOIN Employee e ON e.payGrade.id = p.id " +
            "WHERE p.tenant.id = :tenantId AND p.isActive = true " +
            "AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
            "GROUP BY p.id, p.name, p.basicSalary, p.housingAllowance, p.transportAllowance, " +
            "  p.otherAllowances, p.isActive, p.loanPolicy.id " +
            "ORDER BY (p.basicSalary + p.housingAllowance + p.transportAllowance + p.otherAllowances) DESC")
    List<PayGradeListRow> findFilteredWithEmployeeCount(@Param("tenantId") UUID tenantId,
                                                        @Param("search") String search);
}