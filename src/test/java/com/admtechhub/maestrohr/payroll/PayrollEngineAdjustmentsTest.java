package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.adjustment.AdjustmentBuckets;
import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.PayGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.admtechhub.maestrohr.loan.LoanPolicyService;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;

/**
 * PayrollEngine tests for one-off payroll adjustments (bonuses, reimbursements, fines, advances,
 * voluntary pension), with the REAL sub-calculators. Baseline (standard ₦350,000 grade, 22/22):
 *   Gross 35,000,000 | Pension 2,800,000 | NHF 625,000 | PAYE 4,133,500 | Net 27,441,500.
 *   Base annual taxable 378,900,000 sits in the 18% band, so the marginal rate on adjustments is 18%.
 */
class PayrollEngineAdjustmentsTest {

    private PayrollEngine engine;

    private static final long BASIC = 25_000_000L, HOUSING = 6_000_000L, TRANSPORT = 4_000_000L;
    private static final long GROSS = 35_000_000L;
    private static final long PENSION_EMP = 2_800_000L, NHF = 625_000L, PAYE = 4_133_500L, NET_FULL = 27_441_500L;

    @BeforeEach
    void setUp() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getLongOrDefault(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getDoubleOrDefault(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        LoanPolicyService loanPolicyService = mock(LoanPolicyService.class);
        when(loanPolicyService.getPolicyForEmployee(any())).thenReturn(Optional.empty());
        AttendanceService attendanceService = mock(AttendanceService.class);
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.empty());

        engine = new PayrollEngine(
                new PensionCalculator(settings), new NHFCalculator(settings), new NSITFCalculator(settings),
                new PAYECalculator(settings), loanPolicyService, settings, attendanceService);
    }

    private Employee standard() {
        return Employee.builder().firstName("Ngozi").lastName("Adeyemi").employeeNumber("EMP-100")
                .payGrade(PayGrade.builder().name("G4").basicSalary(BASIC).housingAllowance(HOUSING)
                        .transportAllowance(TRANSPORT).otherAllowances(0L).build())
                .build();
    }

    private Employee minWage() {
        return Employee.builder().firstName("Ada").lastName("Low").employeeNumber("EMP-200")
                .payGrade(PayGrade.builder().name("G1").basicSalary(7_000_000L).housingAllowance(0L)
                        .transportAllowance(0L).otherAllowances(0L).build())
                .build();
    }

    private AdjustmentBuckets buckets(long taxable, long nonTaxable, long preTax, long postTax) {
        return new AdjustmentBuckets(taxable, nonTaxable, preTax, postTax);
    }

    @Test
    void taxableBonus_addsToGross_andTaxedAtMarginalBand() {
        long bonus = 5_000_000L; // ₦50,000
        var r = engine.calculateEmployeePayroll(standard(), 22, 22, 0, 0, 0, 0L, buckets(bonus, 0, 0, 0));

        assertThat(r.getGrossSalary()).isEqualTo(GROSS + bonus);
        assertThat(r.getTaxableEarnings()).isEqualTo(bonus);
        // Marginal tax = bonus × 18% = 900,000; a one-off bonus is NOT annualized ×12.
        assertThat(r.getPayeTax()).isEqualTo(PAYE + 900_000L);
        assertThat(r.getNetSalary()).isEqualTo(GROSS + bonus - PENSION_EMP - NHF - (PAYE + 900_000L));
    }

    @Test
    void nonTaxableReimbursement_addsToGross_butNotToTax() {
        long reimb = 3_000_000L;
        var r = engine.calculateEmployeePayroll(standard(), 22, 22, 0, 0, 0, 0L, buckets(0, reimb, 0, 0));

        assertThat(r.getGrossSalary()).isEqualTo(GROSS + reimb);
        assertThat(r.getNonTaxableEarnings()).isEqualTo(reimb);
        assertThat(r.getPayeTax()).isEqualTo(PAYE); // unchanged
        assertThat(r.getNetSalary()).isEqualTo(GROSS + reimb - PENSION_EMP - NHF - PAYE);
    }

    @Test
    void preTaxVoluntaryPension_reducesTaxAndNet() {
        long avc = 2_000_000L;
        var r = engine.calculateEmployeePayroll(standard(), 22, 22, 0, 0, 0, 0L, buckets(0, 0, avc, 0));

        assertThat(r.getGrossSalary()).isEqualTo(GROSS);
        assertThat(r.getPreTaxDeduction()).isEqualTo(avc);
        // Relief at the margin: −avc × 18% = −360,000.
        assertThat(r.getPayeTax()).isEqualTo(PAYE - 360_000L);
        assertThat(r.getNetSalary()).isEqualTo(GROSS - PENSION_EMP - NHF - (PAYE - 360_000L) - avc);
    }

    @Test
    void postTaxFine_reducesNet_notTax_whenAboveFloor() {
        long fine = 1_000_000L; // ₦10,000
        var r = engine.calculateEmployeePayroll(standard(), 22, 22, 0, 0, 0, 0L, buckets(0, 0, 0, fine));

        assertThat(r.getPayeTax()).isEqualTo(PAYE);
        assertThat(r.getAdjustmentDeduction()).isEqualTo(fine);
        assertThat(r.isAdjustmentCapped()).isFalse();
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - fine);
    }

    @Test
    void postTaxDeduction_cappedToProtectMinimumWageFloor() {
        long huge = 25_000_000L; // would push net below the ₦70,000 (7,000,000 kobo) floor
        var r = engine.calculateEmployeePayroll(standard(), 22, 22, 0, 0, 0, 0L, buckets(0, 0, 0, huge));

        // Net floored at the minimum wage; the deduction is reduced to whatever room existed.
        assertThat(r.getNetSalary()).isEqualTo(7_000_000L);
        assertThat(r.getAdjustmentDeduction()).isEqualTo(NET_FULL - 7_000_000L);
        assertThat(r.isAdjustmentCapped()).isTrue();
    }

    @Test
    void minWageEmployee_stillExemptFromTax_evenWithBonus() {
        var r = engine.calculateEmployeePayroll(minWage(), 22, 22, 0, 0, 0, 0L, buckets(1_000_000L, 0, 0, 0));

        assertThat(r.getPayeTax()).isEqualTo(0L);
        assertThat(r.getTaxableEarnings()).isEqualTo(1_000_000L);
        assertThat(r.getGrossSalary()).isEqualTo(8_000_000L);
    }
}
