package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    // ── applyForLoan: installment computation ─────────────────────────────────

    @Test
    void applyForLoan_calculatesCorrectInstallment() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(emp));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 12_000_000 / 12 = 1_000_000 exactly
        EmployeeLoan result = loanService.applyForLoan(empId, 12_000_000L, 12, LocalDate.now(), "Car loan");

        assertThat(result.getMonthlyInstallment()).isEqualTo(1_000_000L);
        assertThat(result.getRemainingBalance()).isEqualTo(12_000_000L);
    }

    @Test
    void applyForLoan_nonDivisibleAmount_installmentIsFloorDivision() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(emp));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 10_000_000 / 3 = 3_333_333 (floor); last payment trues up: 10M - 2×3_333_333 = 3_333_334
        EmployeeLoan result = loanService.applyForLoan(empId, 10_000_000L, 3, LocalDate.now(), "Phone loan");

        assertThat(result.getMonthlyInstallment()).isEqualTo(3_333_333L);
        assertThat(result.getRemainingBalance()).isEqualTo(10_000_000L);
    }

    // ── pauseLoan / resumeLoan state transitions ──────────────────────────────

    @Test
    void pauseLoan_changesStatusToPaused() {
        UUID loanId = UUID.randomUUID();
        when(loanRepository.findById(loanId))
                .thenReturn(Optional.of(loan(25_000L, 150_000L, 1, 6, LoanStatus.ACTIVE)));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        loanService.pauseLoan(loanId);

        ArgumentCaptor<EmployeeLoan> cap = ArgumentCaptor.forClass(EmployeeLoan.class);
        verify(loanRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(LoanStatus.PAUSED);
    }

    @Test
    void resumeLoan_changesStatusToActive() {
        UUID loanId = UUID.randomUUID();
        when(loanRepository.findById(loanId))
                .thenReturn(Optional.of(loan(25_000L, 100_000L, 2, 6, LoanStatus.PAUSED)));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        loanService.resumeLoan(loanId);

        ArgumentCaptor<EmployeeLoan> cap = ArgumentCaptor.forClass(EmployeeLoan.class);
        verify(loanRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    // ── applyRepaymentsForRun: last installment zeros balance → COMPLETED ─────

    @Test
    void applyRepayments_lastInstallment_completesLoan() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        EmployeeLoan activeLoan = loan(1_000_000L, 1_000_000L, 5, 6, LoanStatus.ACTIVE);
        PayrollEntry entry = mockEntry(emp, 1_000_000L);
        PayrollRun payrollRun = mock(PayrollRun.class);

        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(empId, LoanStatus.ACTIVE))
                .thenReturn(List.of(activeLoan));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        loanService.applyRepaymentsForRun(payrollRun, List.of(entry));

        ArgumentCaptor<EmployeeLoan> cap = ArgumentCaptor.forClass(EmployeeLoan.class);
        verify(loanRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(LoanStatus.COMPLETED);
        assertThat(cap.getValue().getRemainingBalance()).isLessThanOrEqualTo(0L);
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
