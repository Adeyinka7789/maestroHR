package com.admtechhub.maestrohr.loan;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanServiceTest {

    @Mock private EmployeeLoanRepository loanRepository;
    @Mock private LoanRepaymentRepository repaymentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LoanPolicyService loanPolicyService;

    @InjectMocks private LoanService loanService;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(UUID.randomUUID().toString());
        lenient().when(loanPolicyService.getPolicyForEmployee(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private EmployeeLoan loan(long installment, long remaining, int monthsPaid, int repaymentMonths, LoanStatus status) {
        return EmployeeLoan.builder()
                .monthlyInstallment(installment)
                .remainingBalance(remaining)
                .monthsPaid(monthsPaid)
                .repaymentMonths(repaymentMonths)
                .status(status)
                .build();
    }

    @Test
    void normalMonth_deductsInstallment() {
        assertEquals(25_000L, loanService.installmentFor(loan(25_000L, 150_000L, 0, 6, LoanStatus.ACTIVE)));
    }

    @Test
    void finalScheduledMonth_clearsEntireRemainder() {
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

    @Test
    void applyForLoan_calculatesCorrectInstallment() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(emp));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmployeeLoan result = loanService.applyForLoan(empId, 12_000_000L, 12, LocalDate.now(), "Car loan");

        assertThat(result.getMonthlyInstallment()).isEqualTo(1_000_000L);
        assertThat(result.getRemainingBalance()).isEqualTo(12_000_000L);
    }

    @Test
    void applyForLoan_rejectsWhenActiveLoanExists_evenWithoutPolicy() {
        // setUp() stubs loanPolicyService.getPolicyForEmployee(...) to Optional.empty() —
        // the "no LoanPolicy configured for this tenant" scenario. The concurrent-active-loan
        // check must still fire since it's no longer nested inside validateLoanRequest's
        // policy-gated block.
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(emp));
        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(eq(empId), eq(LoanStatus.ACTIVE), any(UUID.class)))
                .thenReturn(List.of(loan(25_000L, 100_000L, 0, 6, LoanStatus.ACTIVE)));

        assertThrows(IllegalArgumentException.class,
                () -> loanService.applyForLoan(empId, 12_000_000L, 12, LocalDate.now(), "Second loan"));

        verify(loanRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void applyForLoan_nonDivisibleAmount_installmentIsFloorDivision() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(emp));
        when(loanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmployeeLoan result = loanService.applyForLoan(empId, 10_000_000L, 3, LocalDate.now(), "Phone loan");

        assertThat(result.getMonthlyInstallment()).isEqualTo(3_333_333L);
        assertThat(result.getRemainingBalance()).isEqualTo(10_000_000L);
    }

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
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        EmployeeLoan pausedLoan = loan(25_000L, 100_000L, 2, 6, LoanStatus.PAUSED);
        pausedLoan.setEmployee(emp);

        when(loanRepository.findById(loanId)).thenReturn(Optional.of(pausedLoan));
        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(eq(empId), eq(LoanStatus.ACTIVE), any(UUID.class)))
                .thenReturn(List.of());
        when(loanRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        loanService.resumeLoan(loanId);

        ArgumentCaptor<EmployeeLoan> cap = ArgumentCaptor.forClass(EmployeeLoan.class);
        verify(loanRepository).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void resumeLoan_rejectsWhenAnotherLoanAlreadyActive() {
        UUID loanId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        EmployeeLoan pausedLoan = loan(25_000L, 100_000L, 2, 6, LoanStatus.PAUSED);
        pausedLoan.setEmployee(emp);

        when(loanRepository.findById(loanId)).thenReturn(Optional.of(pausedLoan));
        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(eq(empId), eq(LoanStatus.ACTIVE), any(UUID.class)))
                .thenReturn(List.of(loan(20_000L, 80_000L, 1, 6, LoanStatus.ACTIVE)));

        assertThrows(IllegalArgumentException.class, () -> loanService.resumeLoan(loanId));

        verify(loanRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void approveLoan_rejectsWhenAnotherLoanAlreadyActive() {
        UUID loanId = UUID.randomUUID();
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        EmployeeLoan pendingLoan = loan(25_000L, 150_000L, 0, 6, LoanStatus.PENDING);
        pendingLoan.setEmployee(emp);

        when(loanRepository.findById(loanId)).thenReturn(Optional.of(pendingLoan));
        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(eq(empId), eq(LoanStatus.ACTIVE), any(UUID.class)))
                .thenReturn(List.of(loan(20_000L, 80_000L, 1, 6, LoanStatus.ACTIVE)));

        assertThrows(IllegalArgumentException.class, () -> loanService.approveLoan(loanId));

        verify(loanRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void applyRepayments_lastInstallment_completesLoan() {
        UUID empId = UUID.randomUUID();
        Employee emp = mockEmployee(empId, "Test Employee");
        EmployeeLoan activeLoan = loan(1_000_000L, 1_000_000L, 5, 6, LoanStatus.ACTIVE);
        PayrollEntry entry = mockEntry(emp, 1_000_000L);
        PayrollRun payrollRun = mock(PayrollRun.class);

        when(loanRepository.findByEmployeeIdAndStatusOrderByCreatedAtAsc(eq(empId), eq(LoanStatus.ACTIVE), any(UUID.class)))
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

        Department dept = mock(Department.class);
        lenient().when(dept.getName()).thenReturn("Engineering");
        lenient().when(emp.getDepartment()).thenReturn(dept);

        PayGrade pg = mock(PayGrade.class);
        lenient().when(pg.getName()).thenReturn("Grade-A");
        lenient().when(emp.getPayGrade()).thenReturn(pg);

        return emp;
    }

    private PayrollEntry mockEntry(Employee emp, long loanDeduction) {
        PayrollEntry entry = org.mockito.Mockito.mock(PayrollEntry.class);
        lenient().when(entry.getEmployee()).thenReturn(emp);
        lenient().when(entry.getLoanDeduction()).thenReturn(loanDeduction);
        return entry;
    }
}