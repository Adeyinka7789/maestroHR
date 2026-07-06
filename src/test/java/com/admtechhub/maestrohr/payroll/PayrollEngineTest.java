package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.attendance.AttendancePolicy;
import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.attendance.DeductionType;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.loan.LoanPolicy;
import com.admtechhub.maestrohr.loan.LoanPolicyService;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for PayrollEngine with REAL sub-calculators — no mocks.
 * Exercises the full NTA 2025 pipeline: proration → pension → NHF → PAYE → deductions.
 * All amounts in kobo (1 NGN = 100 kobo).
 *
 * Standard pay grade used across most tests (₦350,000/month):
 *   Basic ₦250,000 = 25_000_000 | Housing ₦60,000 = 6_000_000 | Transport ₦40,000 = 4_000_000
 *   Gross = 35_000_000
 *
 * Pre-computed statutory deductions for the standard grade (22/22 days):
 *   Pension employee  = Math.round(35_000_000 × 0.08) = 2_800_000
 *   NHF               = Math.round(25_000_000 × 0.025) = 625_000
 *   Annual taxable    = (35M × 12) − (2.8M × 12) − (625k × 12) = 378_900_000
 *     Band 1: 80M → 0 | Band 2: 140M × 15% = 21M | Band 3: 158.9M × 18% = 28_602_000
 *     Annual PAYE = 49_602_000 → monthly = 4_133_500
 *   Net               = 35_000_000 − 2_800_000 − 625_000 − 4_133_500 = 27_441_500
 *   Daily rate        = 35_000_000 / 22 = 1_590_909 (integer division)
 */
class PayrollEngineTest {

    private PayrollEngine engine;
    private PlatformSettingsService platformSettingsService;
    private LoanPolicyService loanPolicyService;
    private AttendanceService attendanceService;

    // Standard pay grade (kobo)
    private static final long BASIC      = 25_000_000L;
    private static final long HOUSING    = 6_000_000L;
    private static final long TRANSPORT  = 4_000_000L;
    private static final long GROSS      = 35_000_000L;

    // Pre-computed statutory amounts for the standard grade, full month
    private static final long PENSION_EMP  = 2_800_000L;
    private static final long NHF          = 625_000L;
    private static final long PAYE         = 4_133_500L;
    private static final long NET_FULL     = 27_441_500L;  // GROSS - PENSION_EMP - NHF - PAYE
    private static final long DAILY_RATE   = 1_590_909L;   // GROSS / 22, integer division
    private static final int  WORKING_DAYS = 22;

    @BeforeEach
    void setUp() {
        // Passthrough stub: any key falls back to the caller's default unless a test
        // overrides that specific key below — mirrors "no settings row configured".
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getLongOrDefault(anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(platformSettingsService.getDoubleOrDefault(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));

        loanPolicyService = mock(LoanPolicyService.class);
        when(loanPolicyService.getPolicyForEmployee(any())).thenReturn(Optional.empty());

        attendanceService = mock(AttendanceService.class);
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.empty());

        engine = new PayrollEngine(
                new PensionCalculator(platformSettingsService),
                new NHFCalculator(platformSettingsService),
                new NSITFCalculator(platformSettingsService),
                new PAYECalculator(platformSettingsService),
                loanPolicyService,
                platformSettingsService,
                attendanceService);
    }

    private Employee standardEmployee() {
        return Employee.builder()
                .firstName("Ngozi")
                .lastName("Adeyemi")
                .employeeNumber("EMP-100")
                .payGrade(PayGrade.builder()
                        .name("Grade 4")
                        .basicSalary(BASIC)
                        .housingAllowance(HOUSING)
                        .transportAllowance(TRANSPORT)
                        .otherAllowances(0L)
                        .build())
                .build();
    }

    // D1 ── full month, no deductions; every key field verified
    @Test
    void d1_standardEmployee_noDeductions_allFieldsCorrect() {
        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 0, 0L);

        assertThat(r.getGrossSalary()).isEqualTo(GROSS);
        assertThat(r.getIsProrated()).isFalse();
        assertThat(r.getPensionEmployee()).isEqualTo(PENSION_EMP);
        assertThat(r.getNhfDeduction()).isEqualTo(NHF);
        assertThat(r.getPayeTax()).isEqualTo(PAYE);
        assertThat(r.getUnpaidLeaveDeduction()).isEqualTo(0L);
        assertThat(r.getAttendanceDeduction()).isEqualTo(0L);
        assertThat(r.getLoanDeduction()).isEqualTo(0L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL);
    }

    // D2 ── mid-month joiner (11/22 days): proration halves all pay components
    //   gross = Math.round(35M × 0.5) = 17_500_000
    @Test
    void d2_midMonthJoiner_11of22Days_grossHalved() {
        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 11, 22, 0, 0, 0, 0L);

        assertThat(r.getIsProrated()).isTrue();
        assertThat(r.getGrossSalary()).isEqualTo(GROSS / 2);          // 17_500_000
        assertThat(r.getBasicSalary()).isEqualTo(BASIC / 2);          // 12_500_000
        assertThat(r.getHousingAllowance()).isEqualTo(HOUSING / 2);   // 3_000_000
        assertThat(r.getTransportAllowance()).isEqualTo(TRANSPORT / 2); // 2_000_000
    }

    // D3 ── minimum-wage employee (₦70,000/month exactly): PAYE = 0, pension and NHF still deducted
    //   Pension_emp = Math.round(7M × 0.08) = 560_000
    //   NHF = Math.round(7M × 0.025) = 175_000
    //   net = 7_000_000 − 560_000 − 175_000 = 6_265_000
    @Test
    void d3_minWageEmployee_payeZero_pensionAndNhfStillApply() {
        Employee minWage = Employee.builder()
                .firstName("Emeka")
                .lastName("Okafor")
                .employeeNumber("EMP-MIN")
                .payGrade(PayGrade.builder()
                        .name("Min Wage")
                        .basicSalary(7_000_000L)
                        .housingAllowance(0L)
                        .transportAllowance(0L)
                        .otherAllowances(0L)
                        .build())
                .build();

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(minWage, 22, 22, 0, 0, 0, 0L);

        assertThat(r.getPayeTax()).isEqualTo(0L);
        assertThat(r.getPensionEmployee()).isEqualTo(560_000L);
        assertThat(r.getNhfDeduction()).isEqualTo(175_000L);
        assertThat(r.getNetSalary()).isEqualTo(6_265_000L);
    }

    // D4 ── loan repayment is post-tax: reduces net by the full amount, tax unchanged
    //   net = NET_FULL − 5_000_000 = 22_441_500
    @Test
    void d4_loanDeduction_reducesNetByFullAmount_taxUnchanged() {
        long loanDeduction = 5_000_000L;

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 0, loanDeduction);

        assertThat(r.getPayeTax()).isEqualTo(PAYE);
        assertThat(r.getLoanDeduction()).isEqualTo(loanDeduction);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - loanDeduction); // 22_441_500
    }

    // D5 ── 3 approved unpaid leave days deducted at daily rate
    //   dailyRate = 35_000_000 / 22 = 1_590_909 (integer division)
    //   unpaidLeaveDeduction = 1_590_909 × 3 = 4_772_727
    @Test
    void d5_unpaidLeaveDeduction_threedays_correctLineItem() {
        long expectedDeduction = DAILY_RATE * 3; // 4_772_727

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 3, 0, 0, 0L);

        assertThat(r.getUnpaidLeaveDeduction()).isEqualTo(expectedDeduction);
        assertThat(r.getAttendanceDeduction()).isEqualTo(0L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - expectedDeduction);
    }

    // D6 ── combined: 1 unpaid leave + 1 absent + loan — all line items correct
    //   unpaidLeaveDeduction = 1_590_909
    //   attendanceDeduction  = 1_590_909
    //   net = NET_FULL − 1_590_909 − 1_590_909 − 2_000_000 = 22_259_682
    @Test
    void d6_combinedDeductions_unpaidLeaveAbsentLoan_allLineItemsCorrect() {
        long loanDeduction = 2_000_000L;

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 1, 1, 0, loanDeduction);

        assertThat(r.getUnpaidLeaveDeduction()).isEqualTo(DAILY_RATE);
        assertThat(r.getAttendanceDeduction()).isEqualTo(DAILY_RATE);
        assertThat(r.getLoanDeduction()).isEqualTo(loanDeduction);
        assertThat(r.getNetSalary())
                .isEqualTo(NET_FULL - DAILY_RATE - DAILY_RATE - loanDeduction); // 22_259_682
    }

    // D7 ── high earner (₦2,800,000/month): annual taxable exceeds ₦11.2M ceiling → Band 6 applies
    //   basic=200M, housing=50M, transport=30M → gross=280_000_000
    //   Annual taxable = (280M × 12) − pension_annual − NHF_annual = 3_031_200_000
    //   3_031_200_000 > 1_120_000_000 (₦11.2M in kobo) → Band 6 overflow taxed at 25%
    //   monthlyPAYE = 57_483_333
    @Test
    void d7_highEarner_band6Applies_payeTaxCorrect() {
        Employee highEarner = Employee.builder()
                .firstName("Babajide")
                .lastName("Tinubu")
                .employeeNumber("EMP-EXEC")
                .payGrade(PayGrade.builder()
                        .name("Executive")
                        .basicSalary(200_000_000L)
                        .housingAllowance(50_000_000L)
                        .transportAllowance(30_000_000L)
                        .otherAllowances(0L)
                        .build())
                .build();

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(highEarner, 22, 22, 0, 0, 0, 0L);

        assertThat(r.getPayeTax()).isGreaterThan(0L);
        // Annual taxable > ₦11,200,000 confirms Band 6 is reached
        assertThat(r.getTaxableIncome()).isGreaterThan(1_120_000_000L);
        assertThat(r.getTaxableIncome()).isEqualTo(3_031_200_000L);
        assertThat(r.getPayeTax()).isEqualTo(57_483_333L);
    }

    // D8 ── loan net-floor branch (previously untested: setUp always mocked
    // getPolicyForEmployee to Optional.empty(), so lines 93-105 in PayrollEngine had zero
    // coverage). A real LoanPolicy is present here with a low netFloorPct (1%, i.e.
    // policyFloor = 350_000), so the binding floor is the platform minimum wage — and
    // min_wage_kobo is overridden to ₦270,000 (27_000_000 kobo), far from both the real
    // ₦70,000 default and the old hardcoded 7_000_000L constant. If the engine still read
    // the hardcoded constant, this loan would NOT be capped (afterStatutory - loan =
    // 22_441_500 > 7_000_000); capping only happens if minNet is sourced from settings.
    //   afterStatutory = NET_FULL = 27_441_500; minNet = max(350_000, 27_000_000) = 27_000_000
    //   effectiveLoanDeduction = 27_441_500 − 27_000_000 = 441_500; netSalary = 27_000_000
    @Test
    void d8_loanFloor_boundBySettingsMinWage_notHardcodedConstant() {
        when(platformSettingsService.getLongOrDefault(eq("min_wage_kobo"), anyLong()))
                .thenReturn(27_000_000L);

        LoanPolicy lowFloorPolicy = LoanPolicy.builder()
                .name("Low Floor")
                .maxAmountKobo(50_000_000L)
                .netFloorPct(new BigDecimal("1.0"))
                .build();
        when(loanPolicyService.getPolicyForEmployee(any())).thenReturn(Optional.of(lowFloorPolicy));

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 0, 5_000_000L);

        assertThat(r.isLoanDeductionCapped()).isTrue();
        assertThat(r.getLoanDeduction()).isEqualTo(441_500L);
        assertThat(r.getNetSalary()).isEqualTo(27_000_000L);
    }

    // D9 — Fix: heavy unpaid-leave deductions alone (no loan, no LoanPolicy configured — the
    // default in setUp()) must never drive net salary negative. The minimum-wage floor
    // previously only protected the LOAN portion (nested inside if(policyOpt.isPresent())), so
    // a heavily-absent employee with zero loan involvement, at a tenant with no LoanPolicy at
    // all, could still see netSalary go negative. The final non-negative clamp now catches
    // this unconditionally.
    @Test
    void d9_noPolicy_heavyUnpaidLeaveAlone_netSalaryFlooredAtZero_notNegative() {
        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 30, 0, 0, 0L);

        assertThat(r.getNetSalary()).isEqualTo(0L);
        assertThat(r.isNetFloorClamped()).isTrue();
    }

    // D10 — Fix: absence/late/unpaid-leave deductions must use the NOMINAL (un-prorated) daily
    // rate, not the period's already-prorated gross - otherwise a mid-month joiner's absence
    // deduction would be computed off a diluted daily rate instead of their real one.
    //   Nominal daily rate = GROSS / 22 = 1_590_909 (DAILY_RATE)
    //   Buggy (prorated-gross) daily rate would have been (GROSS/2) / 22 = 795_454 instead.
    @Test
    void d10_midMonthJoiner_absenceDeduction_usesNominalDailyRate_notProratedDailyRate() {
        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 11, 22, 0, 2, 0, 0L);

        assertThat(r.getIsProrated()).isTrue();
        assertThat(r.getGrossSalary()).isEqualTo(GROSS / 2); // prorated gross - unaffected by this fix
        assertThat(r.getAttendanceDeduction()).isEqualTo(DAILY_RATE * 2); // 3_181_818, not 1_590_908
    }

    // ════════════════════════════════════════════════════════════════════════
    // Late deduction (Stage 3) — AttendancePolicy resolved internally via
    // attendanceService.getEffectivePolicy(employee), mirroring loanPolicyService.
    // ════════════════════════════════════════════════════════════════════════

    // L1 — FLAT type, no free days, no conversion: 4 late days × ₦500 flat = 200,000 kobo
    @Test
    void l1_lateDeduction_flatType_noFreeDays_noConversion() {
        AttendancePolicy policy = AttendancePolicy.builder()
                .name("Standard")
                .lateDeductionType(DeductionType.FLAT)
                .lateDeductionValue(new BigDecimal("50000"))
                .lateFreeCount(0)
                .build();
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.of(policy));

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 4, 0L);

        assertThat(r.getLateDeduction()).isEqualTo(200_000L);
        assertThat(r.getAttendanceDeduction()).isEqualTo(0L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - 200_000L);
    }

    // L2 — PERCENTAGE type, no free days, no conversion: 3 late days × (dailyRate × 5%)
    //   perDay = (long) (1_590_909 × 5.0 / 100.0) = (long) 79_545.45 = 79_545
    //   total = 3 × 79_545 = 238_635
    @Test
    void l2_lateDeduction_percentageType_noFreeDays_noConversion() {
        AttendancePolicy policy = AttendancePolicy.builder()
                .name("Standard")
                .lateDeductionType(DeductionType.PERCENTAGE)
                .lateDeductionValue(new BigDecimal("5.00"))
                .lateFreeCount(0)
                .build();
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.of(policy));

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 3, 0L);

        assertThat(r.getLateDeduction()).isEqualTo(238_635L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - 238_635L);
    }

    // L3 — free count fully absorbs late days: 3 lates <= 5 free -> billableLateDays = 0 -> 0 deduction
    @Test
    void l3_lateDeduction_freeCountFullyAbsorbsLateDays_zeroDeduction() {
        AttendancePolicy policy = AttendancePolicy.builder()
                .name("Standard")
                .lateDeductionType(DeductionType.FLAT)
                .lateDeductionValue(new BigDecimal("100000"))
                .lateFreeCount(5)
                .build();
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.of(policy));

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 3, 0L);

        assertThat(r.getLateDeduction()).isEqualTo(0L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL);
    }

    // L4 — free count partially absorbs: 5 lates - 2 free = 3 billable × ₦500 flat = 150,000
    @Test
    void l4_lateDeduction_freeCountPartiallyAbsorbs_onlyExcessBilled() {
        AttendancePolicy policy = AttendancePolicy.builder()
                .name("Standard")
                .lateDeductionType(DeductionType.FLAT)
                .lateDeductionValue(new BigDecimal("50000"))
                .lateFreeCount(2)
                .build();
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.of(policy));

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 5, 0L);

        assertThat(r.getLateDeduction()).isEqualTo(150_000L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - 150_000L);
    }

    // L5 — conversion enabled, 3 lates = 1 absence: 7 billable late days (0 free) ->
    //   convertedAbsenceDays = 7 / 3 = 2, remainingLateDays = 7 % 3 = 1
    //   lateOnlyDeduction        = 1 × ₦300 flat  = 30_000
    //   convertedAbsenceDeduction = 2 × ₦2,000 flat = 400_000
    //   total = 430_000
    @Test
    void l5_lateDeduction_conversionEnabled_splitsIntoAbsencesAndRemainder() {
        AttendancePolicy policy = AttendancePolicy.builder()
                .name("Standard")
                .lateDeductionType(DeductionType.FLAT)
                .lateDeductionValue(new BigDecimal("30000"))
                .lateFreeCount(0)
                .absenceDeductionType(DeductionType.FLAT)
                .absenceDeductionValue(new BigDecimal("200000"))
                .lateToAbsenceConversionEnabled(true)
                .lateToAbsenceConversionCount(3)
                .build();
        when(attendanceService.getEffectivePolicy(any())).thenReturn(Optional.of(policy));

        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 7, 0L);

        assertThat(r.getLateDeduction()).isEqualTo(430_000L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL - 430_000L);
    }

    // L6 — no AttendancePolicy resolves: 0 late deduction (graceful fallback, mirrors
    // getEffectivePolicy's Optional.empty() precedent — setUp() already stubs this default).
    @Test
    void l6_lateDeduction_noPolicyResolves_zeroDeduction() {
        PayrollEngine.PayrollResult r =
                engine.calculateEmployeePayroll(standardEmployee(), 22, 22, 0, 0, 5, 0L);

        assertThat(r.getLateDeduction()).isEqualTo(0L);
        assertThat(r.getNetSalary()).isEqualTo(NET_FULL);
    }
}
