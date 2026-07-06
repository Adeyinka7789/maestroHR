package com.admtechhub.maestrohr.payroll;

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
     * @return Complete PayrollResult including separate deduction line items
     */
    public PayrollResult calculateEmployeePayroll(Employee employee, int daysWorked, int workingDays,
                                                  int unpaidLeaveDays, int absentDays, int lateDays,
                                                  long loanDeduction) {
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

        // Step 1: Calculate Gross Salary
        Long grossSalary = basicSalary + housingAllowance + transportAllowance + otherAllowances;

        // Step 2: Calculate Pension (on Basic + Housing + Transport)
        var pensionResult = pensionCalculator.calculate(basicSalary, housingAllowance, transportAllowance);

        // Step 3: Calculate NHF (on Basic only)
        Long nhfDeduction = nhfCalculator.calculate(basicSalary);

        // Step 4: Calculate PAYE. payeResult.getMonthlyPAYE() is banded off the NOMINAL annual
        // rate (see PAYECalculator), i.e. what a full-time earner at this salary owes for a
        // full month — so for a partial period it must be prorated the same way gross was,
        // or a mid-month joiner would be charged a full month's tax on a partial month's pay.
        var payeResult = payeCalculator.calculate(grossSalary, pensionResult.getEmployeeContribution(), nhfDeduction, basicSalary, nominalMonthlyGross);
        Long payeTax = payeResult.getMonthlyPAYE();
        if (isProrated) {
            payeTax = Math.round(payeTax * prorationFactor);
        }

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
        long afterStatutory = grossSalary - statutoryDeductions - unpaidLeaveDeduction - attendanceDeduction - lateDeduction;

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

        // Step 8: Calculate Net Salary. Loan repayment is post-tax (Nigerian loan
        // repayments don't reduce taxable income), so it nets out alongside the other
        // post-statutory deductions.
        Long netSalary = afterStatutory - effectiveLoanDeduction;

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
