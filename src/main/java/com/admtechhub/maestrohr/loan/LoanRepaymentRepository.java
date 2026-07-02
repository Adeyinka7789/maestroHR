package com.admtechhub.maestrohr.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped via the {@code @SQLRestriction} on {@link LoanRepayment}.
 */
@Repository
public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, UUID> {

    /**
     * Whether this loan was already repaid in this run. The DB also enforces it
     * (uk_loan_repayment_per_run); this pre-check lets the apply phase skip cleanly on a
     * retry instead of provoking a constraint violation.
     */
    // ✅ SAFE
    @Query("SELECT COUNT(lr) > 0 FROM LoanRepayment lr WHERE lr.loan.id = :loanId AND lr.payrollRun.id = :payrollRunId AND lr.tenant.id = :tenantId")
    boolean existsByLoanIdAndPayrollRunId(@Param("loanId") UUID loanId, @Param("payrollRunId") UUID payrollRunId, @Param("tenantId") UUID tenantId);

    @Query("SELECT lr FROM LoanRepayment lr WHERE lr.payrollRun.id = :payrollRunId AND lr.tenant.id = :tenantId")
    List<LoanRepayment> findByPayrollRunId(@Param("payrollRunId") UUID payrollRunId, @Param("tenantId") UUID tenantId);
}
