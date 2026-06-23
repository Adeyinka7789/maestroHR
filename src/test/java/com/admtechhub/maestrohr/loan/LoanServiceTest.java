package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the loan deduction maths and the approval consistency guard — the two pieces
 * with non-trivial logic. Repository access is mocked; no Spring context / DB.
 */
@ExtendWith(MockitoExtension.class)
class LoanServiceTest {

    @Mock private EmployeeLoanRepository loanRepository;
    @Mock private LoanRepaymentRepository repaymentRepository;
    @Mock private EmployeeRepository employeeRepository;

    @InjectMocks private LoanService loanService;

    private EmployeeLoan loan(long installment, long remaining, int monthsPaid, int repaymentMonths, LoanStatus status) {
        return EmployeeLoan.builder()
                .monthlyInstallment(installment)
                .remainingBalance(remaining)
                .monthsPaid(monthsPaid)
                .repaymentMonths(repaymentMonths)
                .status(status)
                .build();
    }

    // ── installmentFor ────────────────────────────────────────────────────────

    @Test
    void normalMonth_deductsInstallment() {
        // month 1 of 6, plenty remaining → the flat installment
        assertEquals(25_000L, loanService.installmentFor(loan(25_000L, 150_000L, 0, 6, LoanStatus.ACTIVE)));
    }

    @Test
    void finalScheduledMonth_clearsEntireRemainder() {
        // Non-divisible: 100/3 → installment 33. After 2 payments of 33, remaining = 34 on the
        // final (3rd) month. installmentFor must take the whole 34, not 33, so the loan completes.
        assertEquals(34L, loanService.installmentFor(loan(33L, 34L, 2, 3, LoanStatus.ACTIVE)));
    }

    @Test
    void remainingBelowInstallment_capsAtRemaining() {
        assertEquals(10_000L, loanService.installmentFor(loan(25_000L, 10_000L, 1, 6, LoanStatus.ACTIVE)));
    }

    @Test
    void nonActiveOrCleared_deductsNothing() {
        assertEquals(0L, loanService.installmentFor(loan(25_000L, 150_000L, 0, 6, LoanStatus.PAUSED)));
        assertEquals(0L, loanService.installmentFor(loan(25_000L, 0L, 6, 6, LoanStatus.ACTIVE)));
    }

    // ── verifyDeductionsCurrent (approval guard) ───────────────────────────────

    @Test
    void guard_passes_whenStoredMatchesCurrentLoanState() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Jane Doe");
        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(empId, LoanStatus.ACTIVE))
                .thenReturn(List.of(loan(25_000L, 150_000L, 0, 6, LoanStatus.ACTIVE)));

        PayrollEntry entry = mockEntry(emp, 25_000L); // matches the active loan's installment

        assertDoesNotThrow(() -> loanService.verifyDeductionsCurrent(List.of(entry)));
    }

    @Test
    void guard_throws_whenLoanPausedAfterCompute() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Jane Doe");
        // Loan was paused since compute → no active loans now → current deduction is 0.
        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(empId, LoanStatus.ACTIVE))
                .thenReturn(List.of());

        PayrollEntry entry = mockEntry(emp, 25_000L); // payslip still carries the stale 25k

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> loanService.verifyDeductionsCurrent(List.of(entry)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("recompute"));
    }

    private Employee mockEmployee(UUID id, String name) {
        Employee emp = org.mockito.Mockito.mock(Employee.class);
        lenient().when(emp.getId()).thenReturn(id);
        lenient().when(emp.getFullName()).thenReturn(name);
        return emp;
    }

    private PayrollEntry mockEntry(Employee emp, long loanDeduction) {
        PayrollEntry entry = org.mockito.Mockito.mock(PayrollEntry.class);
        lenient().when(entry.getEmployee()).thenReturn(emp);
        lenient().when(entry.getLoanDeduction()).thenReturn(loanDeduction);
        return entry;
    }
}
