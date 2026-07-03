package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.loan.LoanPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Verifies that PayrollEngine correctly calculates unpaid-leave and attendance
 * deductions as separate line items and subtracts them from net pay.
 *
 * All values are in kobo. Sub-calculators (Pension, NHF, PAYE, NSITF) are
 * mocked to fixed amounts so assertions focus purely on the deduction arithmetic.
 */
@ExtendWith(MockitoExtension.class)
class PayrollIntegrationTest {

    @Mock private PensionCalculator  pensionCalculator;
    @Mock private NHFCalculator      nhfCalculator;
    @Mock private NSITFCalculator    nsitfCalculator;
    @Mock private PAYECalculator     payeCalculator;
    @Mock private LoanPolicyService  loanPolicyService;
    @Mock private AttendanceService  attendanceService;

    @InjectMocks private PayrollEngine payrollEngine;

    // ── Pay-grade values (kobo) ───────────────────────────────────────────────
    private static final long BASIC         = 500_000L;
    private static final long HOUSING       = 200_000L;
    private static final long TRANSPORT     = 100_000L;
    private static final long OTHER         = 0L;
    private static final long GROSS         = BASIC + HOUSING + TRANSPORT + OTHER; // 800,000

    // ── Mocked statutory deduction amounts (kobo) ────────────────────────────
    private static final long PENSION_EMP   = 64_000L;
    private static final long PENSION_ER    = 80_000L;
    private static final long NHF           = 12_500L;
    private static final long PAYE          = 50_000L;
    private static final long NSITF_ER      = 8_000L;

    private static final long STATUTORY     = PENSION_EMP + NHF + PAYE; // 126,500

    // ── Period constants ──────────────────────────────────────────────────────
    private static final int  WORKING_DAYS  = 22;
    private static final long DAILY_RATE    = GROSS / WORKING_DAYS; // 36,363

    private Employee employee;

    @BeforeEach
    void setUp() {
        PayGrade payGrade = PayGrade.builder()
                .basicSalary(BASIC)
                .housingAllowance(HOUSING)
                .transportAllowance(TRANSPORT)
                .otherAllowances(OTHER)
                .name("Level 3")
                .build();

        employee = Employee.builder()
                .firstName("Amaka")
                .lastName("Obi")
                .employeeNumber("EMP-001")
                .payGrade(payGrade)
                .build();

        when(pensionCalculator.calculate(BASIC, HOUSING, TRANSPORT))
                .thenReturn(PensionCalculator.PensionResult.builder()
                        .pensionableEarnings(BASIC + HOUSING + TRANSPORT)
                        .employeeContribution(PENSION_EMP)
                        .employerContribution(PENSION_ER)
                        .build());

        when(nhfCalculator.calculate(BASIC)).thenReturn(NHF);

        // No proration in these tests, so nominal monthly gross == GROSS.
        when(payeCalculator.calculate(GROSS, PENSION_EMP, NHF, BASIC, GROSS))
                .thenReturn(PAYECalculator.PAYEResult.builder()
                        .monthlyPAYE(PAYE)
                        .annualGross(GROSS * 12)
                        .annualGrossTaxable(GROSS * 12)
                        .annualRentRelief(0L)
                        .annualTaxableIncome(GROSS * 12)
                        .annualPAYE(PAYE * 12)
                        .build());

        when(nsitfCalculator.calculateEmployerContribution(GROSS)).thenReturn(NSITF_ER);
        // No policy configured — net-floor cap is inactive for these arithmetic tests.
        when(loanPolicyService.getPolicyForEmployee(any())).thenReturn(Optional.empty());
        // No attendance policy configured — late deduction is inactive for these arithmetic tests.
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.empty());
    }

    // ── Test 1 ────────────────────────────────────────────────────────────────
    // 3 approved unpaid leave days → unpaidLeaveDeduction = dailyRate × 3
    @Test
    void threeUnpaidLeaveDays_deductedAsSeperateLineItemFromNet() {
        PayrollEngine.PayrollResult result =
                payrollEngine.calculateEmployeePayroll(employee, WORKING_DAYS, WORKING_DAYS, 3, 0, 0, 0L);

        long expected = DAILY_RATE * 3; // 36,363 × 3 = 108,989 kobo
        assertEquals(expected,         result.getUnpaidLeaveDeduction(),
                "unpaid leave deduction must equal dailyRate × unpaidLeaveDays");
        assertEquals(0L,               result.getAttendanceDeduction(),
                "no attendance deduction when absentDays = 0");
        assertEquals(GROSS - STATUTORY - expected, result.getNetSalary(),
                "net = gross - statutory - unpaidLeaveDeduction");
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────
    // 2 ABSENT attendance records → attendanceDeduction = dailyRate × 2
    @Test
    void twoAbsentDays_deductedAsSeperateLineItemFromNet() {
        PayrollEngine.PayrollResult result =
                payrollEngine.calculateEmployeePayroll(employee, WORKING_DAYS, WORKING_DAYS, 0, 2, 0, 0L);

        long expected = DAILY_RATE * 2; // 36,363 × 2 = 72,726 kobo
        assertEquals(expected, result.getAttendanceDeduction(),
                "attendance deduction must equal dailyRate × absentDays");
        assertEquals(0L,       result.getUnpaidLeaveDeduction(),
                "no unpaid leave deduction when unpaidLeaveDays = 0");
        assertEquals(GROSS - STATUTORY - expected, result.getNetSalary(),
                "net = gross - statutory - attendanceDeduction");
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────
    // No leave or absence → both deductions are zero, net pay equals
    // gross minus statutory deductions only (unchanged from pre-Phase 2 behaviour)
    @Test
    void noDeductions_netPayIsGrossMinusStatutoryOnly() {
        PayrollEngine.PayrollResult result =
                payrollEngine.calculateEmployeePayroll(employee, WORKING_DAYS, WORKING_DAYS, 0, 0, 0, 0L);

        assertEquals(0L,              result.getUnpaidLeaveDeduction());
        assertEquals(0L,              result.getAttendanceDeduction());
        assertEquals(GROSS - STATUTORY, result.getNetSalary(),
                "net = gross - statutory when there are no deductions");
    }
}
