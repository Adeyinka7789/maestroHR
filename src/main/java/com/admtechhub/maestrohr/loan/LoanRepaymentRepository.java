package com.admtechhub.maestrohr.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
    boolean existsByLoanIdAndPayrollRunId(UUID loanId, UUID payrollRunId);
}
