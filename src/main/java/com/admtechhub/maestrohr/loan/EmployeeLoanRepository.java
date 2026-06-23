package com.admtechhub.maestrohr.loan;

import org.springframework.data.jpa.repository.JpaRepository;
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
    List<EmployeeLoan> findByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    /** Active loans for an employee — the set the payroll engine deducts from. */
    List<EmployeeLoan> findByEmployeeIdAndStatusOrderByCreatedAtAsc(UUID employeeId, LoanStatus status);

    /** Every loan in the tenant, newest first — backs the HR loan-management list. */
    List<EmployeeLoan> findAllByOrderByCreatedAtDesc();

    /** True when the employee has any loan row at all (hard-delete dependency guard). */
    boolean existsByEmployeeId(UUID employeeId);
}
