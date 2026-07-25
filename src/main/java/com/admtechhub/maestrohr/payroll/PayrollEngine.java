package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.adjustment.AdjustmentBuckets;
import com.admtechhub.maestrohr.attendance.AttendancePolicy;
import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.attendance.DeductionType;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.loan.LoanPolicy;
import com.admtechhub.maestrohr.loan.LoanPolicyService;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollEngine {

    private final PensionCalculator pensionCalculator;
    private final NHFCalculator nhfCalculator;
    private final NSITFCalculator nsitfCalculator;
    private final PAYECalculator payeCalculator;
    private final LoanPolicyService loanPolicyService;
    private final PlatformSettingsService platformSettingsService;
    private final AttendanceService attendanceService;

    /** Overload with no one-off adjustments (zero buckets) — used by tests and any caller that
     *  computes a plain run. Delegates to the full method below. */
    public PayrollResult calculateEmployeePayroll(Employee employee, int daysWorked, int workingDays,
                                                  int unpaidLeaveDays, int absentDays, int lateDays,
                                                  long loanDeduction) {
        return calculateEmployeePayroll(employee, daysWorked, workingDays, unpaidLeaveDays,
                absentDays, lateDays, loanDeduction, AdjustmentBuckets.zero());
    }

    /**
     * Calculate complete payroll for a single employee.
     *
     * @param employee        Employee with pay grade
     * @param daysWorked      Days worked in month (for mid-month joiner proration)
     * @param workingDays     Total working days in month
     * @param unpaidLeaveDays Approved unpaid leave days in the period — deducted post-statutory
     * @param absentDays      ABSENT attendance records in the period — deducted post-statutory
     * @param lateDays        LATE attendance records in the period — deducted post-statutory via the
     *                        effective {@link AttendancePolicy}, resolved internally the same way
     *                        {@code loanPolicyService.getPolicyForEmployee} resolves the loan policy below.
     * @param loanDeduction   Active-loan repayment for the period (kobo) — deducted post-statutory.
     *                        Calculated by LoanService and passed in; the engine applies the net-floor
     *                        cap and flags the result if the amount was reduced.
     * @param adjustments     One-off payroll adjustments for the period (bonuses, reimbursements,
     *                        fines, advances, voluntary pension), routed by tax treatment. Fixed
     *                        amounts — never prorated. Taxable earnings are taxed at the margin,
     *                        pre-tax deductions relieve tax, post-tax deductions are floor-protected.
     * @return Complete PayrollResult including separate deduction line items
     */
    public PayrollResult calculateEmployeePayroll(Employee employee, int daysWorked, int workingDays,
                                                  int unpaidLeaveDays, int absentDays, int lateDays,
                                                  long loanDeduction, AdjustmentBuckets adjustments) {
        PayGrade payGrade = employee.getPayGrade();

        // Get base salaries from pay grade (all in kobo)
        Long basicSalary = payGrade.getBasicSalary();
        Long housingAllowance = payGrade.getHousingAllowance();
        Long transportAllowance = payGrade.getTransportAllowance();
        Long otherAllowances = payGrade.getOtherAllowances();

        // Nominal (un-prorated) monthly gross — used only for the PAYE minimum-wage
        // exemption test, so a higher earner who worked a partial month is still taxed.
        Long nominalMonthlyGross = basicSalary + housingAllowance + transportAllowance + otherAllowances;

        // Apply proration if days worked < working days
        double prorationFactor = (double) daysWorked / workingDays;
        boolean isProrated = daysWorked < workingDays;

        if (isProrated) {
            basicSalary = Math.round(basicSalary * prorationFactor);
            housingAllowance = Math.round(housingAllowance * prorationFactor);
            transportAllowance = Math.round(transportAllowance * prorationFactor);
            otherAllowances = Math.round(otherAllowances * prorationFactor);
            log.debug("Prorated salary for {}: {} days out of {} (factor: {})",
                    employee.getFullName(), daysWorked, workingDays, prorationFactor);
        }

        // Step 1: Base (pay-grade) gross for this period. Adjustment earnings are added to the
        // PAID gross below but NOT here — they are fixed one-off amounts, not part of the
        // pay-grade run-rate that drives pension/NHF/PAYE banding.
        Long baseGross = basicSalary + housingAllowance + transportAllowance + otherAllowances;

        // One-off adjustment buckets (kobo, never prorated).
        long taxableEarnings    = adjustments.taxableEarnings();
        long nonTaxableEarnings = adjustments.nonTaxableEarnings();
        long preTaxDeduction    = adjustments.preTaxDeductions();
        long postTaxDeduction   = adjustments.postTaxDeductions();

        // Paid gross includes adjustment earnings (taxable + non-taxable).
        Long grossSalary = baseGross + taxableEarnings + nonTaxableEarnings;

        // Step 2: Calculate Pension (on Basic + Housing + Transport)
        var pensionResult = pensionCalculator.calculate(basicSalary, housingAllowance, transportAllowance);

        // Step 3: Calculate NHF (on Basic only)
        Long nhfDeduction = nhfCalculator.calculate(basicSalary);

        // Step 4: Calculate PAYE off the pay-grade run-rate (nominalMonthlyGross), with this
        // period's one-off taxable earnings and pre-tax relief taxed at the margin. The base
        // monthly PAYE is banded off the NOMINAL annual rate, so for a partial period it is
        // prorated the same way gross is; the one-off adjustment tax is added un-prorated (it
        // does not scale with days worked). Total PAYE is floored at 0 (relief can exceed base).
        var payeResult = payeCalculator.calculate(baseGross, pensionResult.getEmployeeContribution(),
                nhfDeduction, basicSalary, nominalMonthlyGross, employee.getAnnualRentPaid(),
                taxableEarnings, preTaxDeduction);
        long basePaye = payeResult.getMonthlyPAYE();
        if (isProrated) {
            basePaye = Math.round(basePaye * prorationFactor);
        }
        Long payeTax = Math.max(0L, basePaye + payeResult.getPeriodAdjustmentTax());

        // Step 5: Calculate NSITF (Employer only)
        Long nsitfEmployer = nsitfCalculator.calculateEmployerContribution(grossSalary);

        // Step 6: Calculate post-statutory deductions (unpaid leave + unexcused absence).
        // Daily rate uses the employee's NOMINAL (un-prorated) gross, not the period's already-
        // prorated gross — otherwise a mid-month joiner's absence/late deduction would be
        // computed off a diluted daily rate instead of their real one. Integer division — no
        // floating point; small rounding difference is acceptable and consistent with how
        // proration is applied elsewhere in this engine.
        Long dailyRateKobo        = nominalMonthlyGross / workingDays;
        Long unpaidLeaveDeduction = dailyRateKobo * unpaidLeaveDays;
        Long attendanceDeduction  = dailyRateKobo * absentDays;

        // Step 6b: Late deduction. The effective AttendancePolicy is resolved internally here,
        // exactly as LoanPolicy is resolved internally in Step 7 below — never accepted as a
        // parameter — so this stays the single source of truth for late-deduction rules.
        Long lateDeduction = calculateLateDeduction(employee, lateDays, dailyRateKobo);

        // Step 7: Net-floor protection — cap loan deduction so the employee's net salary
        // stays >= max(policy.netFloorPct% of gross, Nigerian minimum wage of ₦70,000). The
        // minimum-wage floor itself is PLATFORM-WIDE and applies unconditionally — with or
        // without a configured LoanPolicy; the policy, when present, can only raise the floor
        // higher via its own netFloorPct, never relax or bypass it. Unlike the loan deduction,
        // unpaidLeaveDeduction/attendanceDeduction/lateDeduction are never themselves reduced
        // by this floor — they only tighten how much loan room is left.
        Long statutoryDeductions = pensionResult.getEmployeeContribution() + nhfDeduction + payeTax;
        // Pre-tax adjustment deductions (e.g. voluntary pension) are the employee's own withheld
        // contribution: applied unconditionally alongside statutory/attendance deductions, and NOT
        // floor-protected. Post-tax adjustment deductions ARE floor-protected below, like loans.
        long afterStatutory = grossSalary - statutoryDeductions - unpaidLeaveDeduction
                - attendanceDeduction - lateDeduction - preTaxDeduction;

        long minWageKobo = platformSettingsService.getLongOrDefault("min_wage_kobo", 7_000_000L); // NMW default = ₦70,000
        long minNet = minWageKobo;
        Optional<LoanPolicy> policyOpt = loanPolicyService.getPolicyForEmployee(employee);
        if (policyOpt.isPresent()) {
            long policyFloor = (long) (grossSalary * policyOpt.get().getNetFloorPct().doubleValue() / 100.0);
            minNet = Math.max(minNet, policyFloor);
        }

        long effectiveLoanDeduction = loanDeduction;
        boolean loanDeductionCapped = false;
        if (afterStatutory - effectiveLoanDeduction < minNet) {
            effectiveLoanDeduction = Math.max(0L, afterStatutory - minNet);
            loanDeductionCapped = true;
            log.warn("Loan deduction capped for {}: requested {} kobo → {} kobo (net floor {} kobo)",
                    employee.getFullName(), loanDeduction, effectiveLoanDeduction, minNet);
        }

        // Post-tax ad-hoc deductions (fines, advances) take whatever room remains after the loan,
        // also protected by the net floor. Any uncollected remainder is simply not taken this
        // period — HR can re-log it for the next month.
        long afterLoan = afterStatutory - effectiveLoanDeduction;
        long effectiveAdjustmentDeduction = postTaxDeduction;
        boolean adjustmentCapped = false;
        if (afterLoan - effectiveAdjustmentDeduction < minNet) {
            effectiveAdjustmentDeduction = Math.max(0L, afterLoan - minNet);
            adjustmentCapped = true;
            log.warn("Ad-hoc deduction capped for {}: requested {} kobo → {} kobo (net floor {} kobo)",
                    employee.getFullName(), postTaxDeduction, effectiveAdjustmentDeduction, minNet);
        }

        // Step 8: Calculate Net Salary. Loan repayment and post-tax adjustments are post-tax
        // (they don't reduce taxable income), so they net out alongside the other post-statutory
        // deductions.
        Long netSalary = afterLoan - effectiveAdjustmentDeduction;

        // Final defensive clamp: net salary must never be negative. The floor above only
        // constrains the LOAN portion — it does nothing when unpaid-leave/absence/late
        // deductions ALONE (no loan involved at all) exceed gross, e.g. a heavily-absent
        // employee at a tenant with no LoanPolicy configured. Applies unconditionally.
        boolean netFloorClamped = false;
        if (netSalary < 0) {
            log.warn("Net salary for {} would have been negative ({} kobo); floored at 0",
                    employee.getFullName(), netSalary);
            netSalary = 0L;
            netFloorClamped = true;
        }

        log.info("Payroll complete for {}: Gross={}, Net={}, PAYE={}, Pension={}, NHF={}, UnpaidLeave={}, Absent={}, Late={}, Loan={} (capped={}, netFloorClamped={})",
                employee.getFullName(), grossSalary, netSalary, payeTax,
                pensionResult.getEmployeeContribution(), nhfDeduction, unpaidLeaveDeduction, attendanceDeduction,
                lateDeduction, effectiveLoanDeduction, loanDeductionCapped, netFloorClamped);

        return PayrollResult.builder()
                .employeeId(employee.getId())
                .employeeName(employee.getFullName())
                .employeeNumber(employee.getEmployeeNumber())
                .basicSalary(basicSalary)
                .housingAllowance(housingAllowance)
                .transportAllowance(transportAllowance)
                .otherAllowances(otherAllowances)
                .grossSalary(grossSalary)
                .pensionEmployee(pensionResult.getEmployeeContribution())
                .pensionEmployer(pensionResult.getEmployerContribution())
                .nhfDeduction(nhfDeduction)
                .nsitfEmployer(nsitfEmployer)
                .payeTax(payeTax)
                .otherDeductions(0L)
                .unpaidLeaveDeduction(unpaidLeaveDeduction)
                .attendanceDeduction(attendanceDeduction)
                .lateDeduction(lateDeduction)
                .loanDeduction(effectiveLoanDeduction)
                .loanDeductionCapped(loanDeductionCapped)
                .taxableEarnings(taxableEarnings)
                .nonTaxableEarnings(nonTaxableEarnings)
                .preTaxDeduction(preTaxDeduction)
                .adjustmentDeduction(effectiveAdjustmentDeduction)
                .adjustmentCapped(adjustmentCapped)
                .netFloorClamped(netFloorClamped)
                .netSalary(netSalary)
                .daysWorked(daysWorked)
                .workingDays(workingDays)
                .isProrated(isProrated)
                .taxableIncome(payeResult.getAnnualTaxableIncome())
                .build();
    }

    /**
     * Late deduction for the period, in kobo. Resolves the employee's effective
     * {@link AttendancePolicy} internally (mirrors {@code loanPolicyService.getPolicyForEmployee}) —
     * without a resolvable policy, there is nothing to compute the deduction from, so this
     * returns 0 (graceful fallback, matching {@code getEffectivePolicy}'s {@code Optional.empty()}
     * precedent).
     *
     * <p>Calculation order:
     * <ol>
     *   <li>{@code billableLateDays = max(0, lateDays - policy.lateFreeCount)}</li>
     *   <li>if late-to-absence conversion is enabled with a positive count, split billableLateDays
     *       into {@code convertedAbsenceDays} (whole conversions) and {@code remainingLateDays}
     *       (the remainder) — otherwise all of it is {@code remainingLateDays}</li>
     *   <li>{@code remainingLateDays} is charged at the policy's late rate/type,
     *       {@code convertedAbsenceDays} at the policy's absence rate/type, and the two sums added</li>
     * </ol>
     *
     * <p>{@code convertedAbsenceDays} is a deduction-only concept: it is never written back to
     * {@code absentDays}, {@code AttendanceRecord.status}, or any other attendance-status field —
     * it only changes which rate a late day is billed at for this payroll calculation.
     */
    private Long calculateLateDeduction(Employee employee, int lateDays, long dailyRateKobo) {
        Optional<AttendancePolicy> policyOpt = attendanceService.getEffectivePolicy(employee);
        if (policyOpt.isEmpty()) {
            return 0L;
        }
        AttendancePolicy policy = policyOpt.get();

        int billableLateDays = Math.max(0, lateDays - policy.getLateFreeCount());

        int convertedAbsenceDays;
        int remainingLateDays;
        Integer conversionCount = policy.getLateToAbsenceConversionCount();
        if (Boolean.TRUE.equals(policy.getLateToAbsenceConversionEnabled())
                && conversionCount != null && conversionCount > 0) {
            convertedAbsenceDays = billableLateDays / conversionCount;
            remainingLateDays = billableLateDays % conversionCount;
        } else {
            convertedAbsenceDays = 0;
            remainingLateDays = billableLateDays;
        }

        long lateOnlyDeduction = deductionForDays(
                policy.getLateDeductionType(), policy.getLateDeductionValue(), remainingLateDays, dailyRateKobo);
        long convertedAbsenceDeduction = deductionForDays(
                policy.getAbsenceDeductionType(), policy.getAbsenceDeductionValue(), convertedAbsenceDays, dailyRateKobo);

        return lateOnlyDeduction + convertedAbsenceDeduction;
    }

    /**
     * FLAT: {@code days * value} (value is a per-day kobo amount).
     * PERCENTAGE: {@code days * (dailyRateKobo * value / 100)} — the double-based percentage
     * math mirrors the existing {@code policy.getNetFloorPct().doubleValue()} idiom used for the
     * loan net-floor calculation above, for consistency.
     */
    private long deductionForDays(DeductionType type, BigDecimal value, int days, long dailyRateKobo) {
        if (days <= 0) {
            return 0L;
        }
        long perDay = (type == DeductionType.FLAT)
                ? value.longValue()
                : (long) (dailyRateKobo * value.doubleValue() / 100.0);
        return perDay * days;
    }

    @lombok.Builder
    @lombok.Data
    public static class PayrollResult {
        private UUID employeeId;
        private String employeeName;
        private String employeeNumber;
        private Long basicSalary;
        private Long housingAllowance;
        private Long transportAllowance;
        private Long otherAllowances;
        private Long grossSalary;
        private Long pensionEmployee;
        private Long pensionEmployer;
        private Long nhfDeduction;
        private Long nsitfEmployer;
        private Long payeTax;
        private Long otherDeductions;
        private Long unpaidLeaveDeduction;
        private Long attendanceDeduction;
        private Long lateDeduction;
        private Long loanDeduction;
        /** True when the loan deduction was reduced to protect the employee's net salary floor. */
        private boolean loanDeductionCapped;
        /** One-off TAXABLE earning adjustments added to gross (kobo). */
        private Long taxableEarnings;
        /** One-off NON_TAXABLE earning adjustments added to gross (kobo). */
        private Long nonTaxableEarnings;
        /** One-off PRE_TAX deduction adjustments (voluntary pension etc.) — relief + reduces net (kobo). */
        private Long preTaxDeduction;
        /** One-off POST_TAX deduction adjustments actually applied after floor protection (kobo). */
        private Long adjustmentDeduction;
        /** True when post-tax adjustment deductions were reduced to protect the net floor. */
        private boolean adjustmentCapped;
        /** True when the platform-wide minimum-wage/non-negative floor clamped net salary at 0
         *  (independent of whether a LoanPolicy is configured). */
        private boolean netFloorClamped;
        private Long netSalary;
        private Integer daysWorked;
        private Integer workingDays;
        private Boolean isProrated;
        private Long taxableIncome;
    }
}
