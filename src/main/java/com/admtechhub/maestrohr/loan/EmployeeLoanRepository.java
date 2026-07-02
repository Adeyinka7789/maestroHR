package com.admtechhub.maestrohr.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped via the {@code @SQLRestriction} on {@link EmployeeLoan} — every finder
 * here only ever returns the current tenant's rows.
 */
@Repository
public interface EmployeeLoanRepository extends JpaRepository<EmployeeLoan, UUID> {

    /** All loans for an employee, newest first — backs the employee-detail loan card. */
    @Query("SELECT l FROM EmployeeLoan l WHERE l.employee.id = :employeeId AND l.tenant.id = :tenantId ORDER BY l.createdAt DESC")
    List<EmployeeLoan> findByEmployeeIdOrderByCreatedAtDesc(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    /** Active loans for an employee — the set the payroll engine deducts from. */
    @Query("SELECT l FROM EmployeeLoan l WHERE l.employee.id = :employeeId AND l.status = :status AND l.tenant.id = :tenantId ORDER BY l.createdAt ASC")
    List<EmployeeLoan> findByEmployeeIdAndStatusOrderByCreatedAtAsc(@Param("employeeId") UUID employeeId, @Param("status") LoanStatus status, @Param("tenantId") UUID tenantId);

    /** Every loan in the tenant, newest first — backs the HR loan-management list. */
    List<EmployeeLoan> findAllByOrderByCreatedAtDesc();

    /** True when the employee has any loan row at all (hard-delete dependency guard). */
    @Query("SELECT COUNT(l) > 0 FROM EmployeeLoan l WHERE l.employee.id = :employeeId AND l.tenant.id = :tenantId")
    boolean existsByEmployeeId(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    /**
     * Find all ACTIVE loans for a batch of employee IDs.
     * Single query replaces N individual lookups in payroll computation.
     */
    @Query("SELECT l FROM EmployeeLoan l " +
            "JOIN FETCH l.employee " +
            "WHERE l.employee.id IN :employeeIds " +
            "AND l.status = 'ACTIVE' " +
            "ORDER BY l.employee.id, l.createdAt ASC")
    List<EmployeeLoan> findActiveLoansByEmployeeIds(@Param("employeeIds") List<UUID> employeeIds);
}
