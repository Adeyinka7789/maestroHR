package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Employee loans / salary advances. All money is in kobo (Long).
 *
 * <p><b>Two-phase payroll integration</b> (the central correctness rule):
 * <ul>
 *   <li><b>Calculate</b> — {@link #computeLoanDeductionForEmployee} sums the installment due
 *       across an employee's ACTIVE loans. It mutates nothing, so the re-runnable
 *       {@code PayrollRunService.computePayroll} can call it any number of times without ever
 *       double-charging. The result is stored on {@code PayrollEntry.loanDeduction}.</li>
 *   <li><b>Apply</b> — {@link #applyRepaymentsForRun} is invoked once, at payroll approval. For
 *       each ACTIVE loan it writes a {@link LoanRepayment} ledger row (guarded by the DB
 *       unique constraint <i>and</i> a pre-check), decrements the balance, bumps
 *       {@code monthsPaid}, and flips the loan to {@code COMPLETED} when it reaches zero.</li>
 * </ul>
 *
 * <p>Both phases share {@link #installmentFor} so the figure shown on the payslip equals the
 * amount actually taken off the loan — provided loans are not edited between compute and
 * approval. Editing loans after compute requires a recompute while the run is still DRAFT,
 * exactly like editing a pay grade mid-run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanService {

    private final EmployeeLoanRepository loanRepository;
    private final LoanRepaymentRepository repaymentRepository;
    private final EmployeeRepository employeeRepository;
    private final LoanPolicyService loanPolicyService;
    private final AuditTrailService auditTrailService;

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * Apply for a loan on behalf of {@code employeeId}. The request is created PENDING — inert
     * until an HR/Finance user approves it ({@link #approveLoan}). The monthly installment is the
     * principal divided over {@code repaymentMonths} (integer kobo); any rounding remainder is
     * cleared by the final installment (see {@link #installmentFor}). The loan is stamped with the
     * employee's tenant and the applicant's email (the caller).
     *
     * <p>The caller is responsible for the IDOR guard — a self-service employee must only ever pass
     * their own id (the self-service controller resolves the employee from the session).
     */
    @Transactional
    public EmployeeLoan applyForLoan(UUID employeeId, long loanAmountKobo, int repaymentMonths,
                                     LocalDate startDate, String description) {
        if (loanAmountKobo <= 0) {
            throw new IllegalArgumentException("Loan amount must be greater than zero.");
        }
        if (repaymentMonths <= 0) {
            throw new IllegalArgumentException("Repayment months must be greater than zero.");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("Start date is required.");
        }

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeId));

        // Platform-wide invariant — one active loan per employee — enforced unconditionally,
        // independent of whether a LoanPolicy is configured for this tenant/employee.
        assertNoActiveLoan(employee);

        // Policy validation — throws IllegalArgumentException on violation
        loanPolicyService.validateLoanRequest(employee, loanAmountKobo, repaymentMonths);

        long installment = loanAmountKobo / repaymentMonths;
        if (installment <= 0) {
            // More months than kobo — nonsensical schedule; one would never deduct anything.
            throw new IllegalArgumentException("Monthly installment rounds to zero; reduce the repayment months.");
        }

        // Snapshot policy fields onto the loan for historical accuracy
        BigDecimal interestRatePct = BigDecimal.ZERO;
        UUID loanPolicyId = null;
        var policyOpt = loanPolicyService.getPolicyForEmployee(employee);
        if (policyOpt.isPresent()) {
            interestRatePct = policyOpt.get().getInterestRatePct();
            loanPolicyId = policyOpt.get().getId();
        }

        EmployeeLoan loan = EmployeeLoan.builder()
                .tenant(employee.getTenant())
                .employee(employee)
                .loanAmount(loanAmountKobo)
                .monthlyInstallment(installment)
                .remainingBalance(loanAmountKobo)
                .repaymentMonths(repaymentMonths)
                .monthsPaid(0)
                .status(LoanStatus.PENDING)
                .startDate(startDate)
                .description(description)
                .createdBy(currentUserEmail())
                .interestRatePct(interestRatePct)
                .loanPolicyId(loanPolicyId)
                .build();

        EmployeeLoan saved = loanRepository.save(loan);
        log.info("Loan application {} created for employee {}: amount={} installment={} over {} months",
                saved.getId(), employeeId, loanAmountKobo, installment, repaymentMonths);
        return saved;
    }

    /** Approve a PENDING loan request → ACTIVE; it starts deducting from the next payroll run. */
    @Transactional
    public EmployeeLoan approveLoan(UUID loanId) {
        EmployeeLoan loan = requireLoan(loanId);
        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new IllegalStateException("Only a PENDING loan request can be approved.");
        }
        // The application-time check in applyForLoan only sees ACTIVE loans, so two requests
        // filed while both were PENDING would otherwise both pass — re-check here, right
        // before the state flip that actually produces a second ACTIVE loan.
        assertNoActiveLoan(loan.getEmployee());
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setDecidedBy(currentUserEmail());
        loan.setDecidedAt(java.time.OffsetDateTime.now());
        loan.setRejectionReason(null);
        EmployeeLoan saved = saveActiveLoan(loan);
        auditTrailService.record(loan.getEmployee().getTenant().getId(), currentUserEmail(), "LOAN_APPROVED",
                "LOAN", loanId.toString(), "/api/loans/" + loanId + "/approve", "POST",
                null, 200, "Loan approved for " + loan.getEmployee().getFullName());
        log.info("Loan {} approved by {}", loanId, loan.getDecidedBy());
        return saved;
    }

    /** Reject a PENDING loan request → REJECTED, recording the reason. Terminal. */
    @Transactional
    public EmployeeLoan rejectLoan(UUID loanId, String reason) {
        EmployeeLoan loan = requireLoan(loanId);
        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new IllegalStateException("Only a PENDING loan request can be rejected.");
        }
        loan.setStatus(LoanStatus.REJECTED);
        loan.setDecidedBy(currentUserEmail());
        loan.setDecidedAt(java.time.OffsetDateTime.now());
        loan.setRejectionReason(reason != null && !reason.isBlank() ? reason.trim() : "No reason provided");
        EmployeeLoan saved = loanRepository.save(loan);
        auditTrailService.record(loan.getEmployee().getTenant().getId(), currentUserEmail(), "LOAN_REJECTED",
                "LOAN", loanId.toString(), "/api/loans/" + loanId + "/reject", "POST",
                null, 200, "Loan rejected. Reason: " + loan.getRejectionReason());
        log.info("Loan {} rejected by {}", loanId, loan.getDecidedBy());
        return saved;
    }

    /** Pause an ACTIVE loan so it is skipped on future runs. */
    @Transactional
    public EmployeeLoan pauseLoan(UUID loanId) {
        EmployeeLoan loan = requireLoan(loanId);
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE loan can be paused.");
        }
        loan.setStatus(LoanStatus.PAUSED);
        return loanRepository.save(loan);
    }

    /** Resume a PAUSED loan back into the deduction schedule. */
    @Transactional
    public EmployeeLoan resumeLoan(UUID loanId) {
        EmployeeLoan loan = requireLoan(loanId);
        if (loan.getStatus() != LoanStatus.PAUSED) {
            throw new IllegalStateException("Only a PAUSED loan can be resumed.");
        }
        // Resuming flips a loan back to ACTIVE without going through applyForLoan/approveLoan,
        // so it needs its own re-check: the employee could have another loan ACTIVE by now.
        assertNoActiveLoan(loan.getEmployee());
        loan.setStatus(LoanStatus.ACTIVE);
        return saveActiveLoan(loan);
    }

    /** Cancel/write off a loan. Terminal: a COMPLETED or already-CANCELLED loan cannot be cancelled. */
    @Transactional
    public EmployeeLoan cancelLoan(UUID loanId) {
        EmployeeLoan loan = requireLoan(loanId);
        if (loan.getStatus() == LoanStatus.COMPLETED || loan.getStatus() == LoanStatus.CANCELLED) {
            throw new IllegalStateException("Loan is already " + loan.getStatus() + " and cannot be cancelled.");
        }
        loan.setStatus(LoanStatus.CANCELLED);
        return loanRepository.save(loan);
    }

    /**
     * Write off the remaining balance of any non-COMPLETED, non-CANCELLED loan.
     * Sets {@code remainingBalance = 0}, flips status to COMPLETED, records the waiver fields,
     * and writes a {@link LoanRepayment} ledger row with {@link RepaymentType#WAIVER} for audit.
     * Only FINANCE_OFFICER / HR_ADMIN callers should invoke this; the controller enforces the role.
     */
    @Transactional
    public EmployeeLoan waiveLoan(UUID loanId, String reason, String waivedByEmail) {
        EmployeeLoan loan = requireLoan(loanId);
        if (loan.getStatus() == LoanStatus.COMPLETED || loan.getStatus() == LoanStatus.CANCELLED) {
            throw new IllegalStateException("Loan is already " + loan.getStatus() + " and cannot be waived.");
        }
        long waivedAmount = loan.getRemainingBalance() != null ? loan.getRemainingBalance() : 0L;

        repaymentRepository.save(LoanRepayment.builder()
                .tenant(loan.getTenant())
                .loan(loan)
                .payrollRun(null)
                .amount(waivedAmount)
                .repaymentType(RepaymentType.WAIVER)
                .build());

        loan.setRemainingBalance(0L);
        loan.setStatus(LoanStatus.COMPLETED);
        loan.setWaiverReason(reason != null && !reason.isBlank() ? reason.trim() : "No reason provided");
        loan.setWaivedAt(OffsetDateTime.now());
        loan.setWaivedBy(waivedByEmail);
        EmployeeLoan saved = loanRepository.save(loan);
        log.info("Loan {} waived by {}; amount cleared = {} kobo", loanId, waivedByEmail, waivedAmount);
        return saved;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EmployeeLoan> getLoansByEmployee(UUID employeeId) {
        return loanRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId, currentTenantId());
    }

    @Transactional(readOnly = true)
    public List<EmployeeLoan> getLoansByTenant() {
        return loanRepository.findAllByOrderByCreatedAtDesc();
    }

    /** True when the employee has any loan record — feeds the hard-delete dependency guard. */
    @Transactional(readOnly = true)
    public boolean hasAnyLoan(UUID employeeId) {
        return loanRepository.existsByEmployeeId(employeeId, currentTenantId());
    }

    /** Total outstanding balance (naira) across all ACTIVE loans — used in exit settlement. */
    @Transactional(readOnly = true)
    public java.math.BigDecimal getOutstandingLoanBalance(UUID employeeId) {
        long kobo = loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(employeeId, LoanStatus.ACTIVE, currentTenantId())
                .stream()
                .mapToLong(l -> l.getRemainingBalance() != null ? l.getRemainingBalance() : 0L)
                .sum();
        return java.math.BigDecimal.valueOf(kobo, 2);
    }

    // ── Payroll integration ──────────────────────────────────────────────────

    /**
     * Phase 1 (calculate). Total loan deduction (kobo) for the employee this cycle: the sum of
     * {@link #installmentFor} over their ACTIVE loans. Pure read — safe to call on every
     * recompute.
     */
    @Transactional(readOnly = true)
    public long computeLoanDeductionForEmployee(UUID employeeId) {
        long total = 0;
        for (EmployeeLoan loan : loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(employeeId, LoanStatus.ACTIVE, currentTenantId())) {
            total += installmentFor(loan);
        }
        return total;
    }

    /**
     * Consistency guard run at approval, before {@link #applyRepaymentsForRun}. Verifies each
     * entry's stored {@code loanDeduction} (locked at compute) still equals what the employee's
     * ACTIVE loans would produce now. They diverge only when a loan was paused / cancelled / added
     * (or another run completed one) between this run's compute and its approval — in which case
     * the payslip total and the about-to-be-written ledger would disagree, leaving the employee
     * short-paid or the company under-recovered.
     *
     * <p>Throws {@link IllegalStateException} on the first mismatch so the caller can surface a
     * "reject and recompute" message; recompute (available while DRAFT) refreshes the stored
     * figures and the next approval passes. Checking the per-entry total is sufficient: the
     * payslip shows a single loan line and the ledger sum equals that total, so offsetting
     * per-loan changes that leave the total unchanged are harmless and correctly pass.
     */
    @Transactional(readOnly = true)
    public void verifyDeductionsCurrent(List<PayrollEntry> entries) {
        for (PayrollEntry entry : entries) {
            Employee employee = entry.getEmployee();
            if (employee == null) {
                continue;
            }
            // When the net-floor cap was applied, the stored amount is intentionally lower than
            // what uncapped installments would sum to — skip the check in that case.
            if (Boolean.TRUE.equals(entry.getLoanDeductionCapped())) {
                continue;
            }
            long stored = entry.getLoanDeduction() != null ? entry.getLoanDeduction() : 0L;
            long current = computeLoanDeductionForEmployee(employee.getId());
            if (stored != current) {
                throw new IllegalStateException(
                        "Loan details for " + employee.getFullName() + " changed since this payroll "
                                + "was computed. Reject the run and recompute before approving.");
            }
        }
    }

    /**
     * Phase 2 (apply). Called once when a payroll run is approved. For every entry's employee,
     * deducts each ACTIVE loan's installment from its balance and records a ledger row. The
     * {@code loan_repayments} unique constraint (loan, run) plus the
     * {@link LoanRepaymentRepository#existsByLoanIdAndPayrollRunId} pre-check make this
     * idempotent: a re-approval re-entry is a no-op rather than a double charge.
     */
    @Transactional
    public void applyRepaymentsForRun(PayrollRun run, List<PayrollEntry> entries) {
        for (PayrollEntry entry : entries) {
            Employee employee = entry.getEmployee();
            if (employee == null) {
                continue;
            }
            // idx_employee_loans_one_active (V48) plus the assertNoActiveLoan checks in
            // applyForLoan/approveLoan/resumeLoan guarantee at most one ACTIVE loan per
            // employee, so there is nothing left to iterate here.
            Optional<EmployeeLoan> activeLoan =
                    loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(employee.getId(), LoanStatus.ACTIVE, currentTenantId())
                            .stream().findFirst();
            if (activeLoan.isEmpty()) {
                continue;
            }
            EmployeeLoan loan = activeLoan.get();

            if (repaymentRepository.existsByLoanIdAndPayrollRunId(loan.getId(), run.getId(), currentTenantId())) {
                continue; // already applied for this run — idempotent skip
            }

            // When the net-floor cap was applied at compute time, entry.loanDeduction is the
            // capped budget so the ledger matches the payslip deduction exactly.
            long budget = (entry.getLoanDeduction() != null) ? entry.getLoanDeduction() : Long.MAX_VALUE;
            long amount = Math.min(installmentFor(loan), budget);
            if (amount <= 0) {
                continue;
            }

            repaymentRepository.save(LoanRepayment.builder()
                    .tenant(loan.getTenant())
                    .loan(loan)
                    .payrollRun(run)
                    .amount(amount)
                    .repaymentType(RepaymentType.STANDARD)
                    .build());

            loan.setRemainingBalance(loan.getRemainingBalance() - amount);
            loan.setMonthsPaid(loan.getMonthsPaid() + 1);
            if (loan.getRemainingBalance() <= 0) {
                loan.setRemainingBalance(0L);
                loan.setStatus(LoanStatus.COMPLETED);
            }
            loanRepository.save(loan);

            log.info("Applied loan repayment: loan={} run={} amount={} remaining={} status={}",
                    loan.getId(), run.getId(), amount, loan.getRemainingBalance(), loan.getStatus());
        }
    }

    /**
     * Phase 2 (reverse). Symmetric undo of {@link #applyRepaymentsForRun}: finds every active
     * (non-reversed) {@link LoanRepayment} row written for this run and walks each one back —
     * restores the balance, decrements monthsPaid, and re-activates a loan that was completed by
     * this run. The row is soft-deleted (reversedAt stamped) rather than hard-deleted so the
     * ledger retains a full audit trail of what was deducted and then returned.
     *
     * <p>Idempotent: a row already stamped with reversedAt is skipped.
     */
    @Transactional
    public void reverseRepaymentsForRun(PayrollRun run) {
        List<LoanRepayment> repayments = repaymentRepository.findByPayrollRunId(run.getId(), currentTenantId());
        for (LoanRepayment repayment : repayments) {
            if (repayment.getReversedAt() != null) {
                continue; // already reversed — idempotent skip
            }
            EmployeeLoan loan = repayment.getLoan();
            if (loan == null) {
                continue;
            }
            long amount = repayment.getAmount() != null ? repayment.getAmount() : 0L;

            loan.setRemainingBalance(loan.getRemainingBalance() + amount);
            loan.setMonthsPaid(Math.max(0, loan.getMonthsPaid() - 1));
            if (loan.getStatus() == LoanStatus.COMPLETED && loan.getRemainingBalance() > 0) {
                loan.setStatus(LoanStatus.ACTIVE);
            }
            loanRepository.save(loan);

            repayment.setReversedAt(OffsetDateTime.now());
            repaymentRepository.save(repayment);

            log.info("Reversed loan repayment: repayment={} loan={} run={} amount={} restoredBalance={}",
                    repayment.getId(), loan.getId(), run.getId(), amount, loan.getRemainingBalance());
        }
    }

    /**
     * The amount (kobo) to deduct from this loan in the current cycle:
     * the monthly installment, capped at the remaining balance, except on the final scheduled
     * month — when {@code monthsPaid} would reach {@code repaymentMonths} — where the entire
     * remaining balance is taken so integer-division rounding never strands a few kobo. Returns
     * 0 for a non-ACTIVE or already-cleared loan.
     */
    long installmentFor(EmployeeLoan loan) {
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            return 0;
        }
        long remaining = loan.getRemainingBalance() != null ? loan.getRemainingBalance() : 0L;
        if (remaining <= 0) {
            return 0;
        }
        boolean finalMonth = loan.getMonthsPaid() + 1 >= loan.getRepaymentMonths();
        if (finalMonth) {
            return remaining;
        }
        return Math.min(loan.getMonthlyInstallment(), remaining);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final String ACTIVE_LOAN_CONFLICT_MESSAGE =
            "Employee already has an active loan. The existing loan must be completed or waived before a new application is submitted.";

    /**
     * Platform-wide invariant — at most one ACTIVE loan per employee. Enforced unconditionally
     * (independent of whether a LoanPolicy is configured) from every path that can produce a
     * second ACTIVE loan: new applications, approvals, and resumes.
     */
    private void assertNoActiveLoan(Employee employee) {
        if (!loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(employee.getId(), LoanStatus.ACTIVE, currentTenantId()).isEmpty()) {
            throw new IllegalArgumentException(ACTIVE_LOAN_CONFLICT_MESSAGE);
        }
    }

    /**
     * Saves a loan whose status was just flipped to ACTIVE, flushing immediately so the
     * {@code idx_employee_loans_one_active} partial unique index is checked synchronously here.
     * This is the backstop for the race {@link #assertNoActiveLoan} can't close on its own
     * (two concurrent approve/resume calls both passing the pre-check before either commits) —
     * translates the resulting constraint violation into the same user-facing message instead
     * of letting a raw SQL exception surface.
     */
    private EmployeeLoan saveActiveLoan(EmployeeLoan loan) {
        try {
            return loanRepository.saveAndFlush(loan);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(ACTIVE_LOAN_CONFLICT_MESSAGE);
        }
    }

    private EmployeeLoan requireLoan(UUID loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private UUID currentTenantId() {
        return UUID.fromString(com.admtechhub.maestrohr.auth.TenantContext.getCurrentTenant());
    }

    /**
     * Batch version: computes loan deductions for multiple employees in a single query.
     * Used by payroll computation to avoid N+1 on the loan table.
     *
     * @return Map of employeeId → total loan deduction in kobo
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> computeLoanDeductionsBatch(List<UUID> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> result = new java.util.HashMap<>();
        for (UUID id : employeeIds) {
            result.put(id, 0L);
        }

        List<List<UUID>> partitions = com.google.common.collect.Lists.partition(employeeIds, 500);

        for (List<UUID> partition : partitions) {
            List<EmployeeLoan> activeLoans = loanRepository
                    .findActiveLoansByEmployeeIds(partition);

            for (EmployeeLoan loan : activeLoans) {
                UUID empId = loan.getEmployee().getId();
                long installment = installmentFor(loan);
                result.merge(empId, installment, Long::sum);
            }
        }

        return result;
    }
}
