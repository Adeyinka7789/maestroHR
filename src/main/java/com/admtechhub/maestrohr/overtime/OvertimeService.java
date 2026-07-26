package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.adjustment.PayrollAdjustmentService;
import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.employee.PayGrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Overtime &amp; shift-allowance calculator (see V64). Bridges the Attendance module to payroll:
 * {@link #computeForPeriod} reads each active employee's attendance for a month, applies the
 * tenant {@link OvertimePolicy} rate card, and produces a DRAFT {@link OvertimeEntry}; HR reviews
 * and {@link #approve}s, which emits a PENDING {@code OVERTIME} payroll adjustment through
 * {@link PayrollAdjustmentService} so the run consumes it via the standard V61 path. Weekday hours
 * beyond {@code standardDailyHours} bill at the weekday multiplier; all weekend hours bill at the
 * weekend multiplier. (Public-holiday classification is future work — see the reserved holiday
 * multiplier.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OvertimeService {

    private final OvertimePolicyRepository policyRepository;
    private final OvertimeEntryRepository entryRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final PayrollAdjustmentService payrollAdjustmentService;
    private final PublicHolidayService publicHolidayService;
    private final com.admtechhub.maestrohr.notification.NotificationService notificationService;

    /** Outcome of a compute sweep, surfaced to the user as a banner. */
    public record ComputeResult(int employeesScanned, int entriesWithOvertime) {}

    // ── Policy ───────────────────────────────────────────────────────────────────

    /** The tenant's active policy, or an in-memory default (not persisted until edited). */
    @Transactional(readOnly = true)
    public OvertimePolicy getPolicyOrDefault() {
        return policyRepository.findFirstByActiveTrue()
                .orElseGet(() -> OvertimePolicy.builder().tenantId(currentTenantId()).build());
    }

    @Transactional
    public OvertimePolicy updatePolicy(BigDecimal standardDailyHours, int standardMonthlyHours,
                                       BigDecimal weekdayMultiplier, BigDecimal weekendMultiplier) {
        if (standardDailyHours == null || standardDailyHours.signum() <= 0
                || standardDailyHours.compareTo(new BigDecimal("24")) > 0) {
            throw new IllegalArgumentException("Standard daily hours must be between 0 and 24.");
        }
        if (standardMonthlyHours <= 0) {
            throw new IllegalArgumentException("Standard monthly hours must be greater than zero.");
        }
        if (weekdayMultiplier == null || weekdayMultiplier.compareTo(BigDecimal.ONE) < 0
                || weekendMultiplier == null || weekendMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Multipliers must be at least 1.0.");
        }
        OvertimePolicy policy = policyRepository.findFirstByActiveTrue()
                .orElseGet(() -> OvertimePolicy.builder().tenantId(currentTenantId()).build());
        policy.setStandardDailyHours(standardDailyHours);
        policy.setStandardMonthlyHours(standardMonthlyHours);
        policy.setWeekdayMultiplier(weekdayMultiplier);
        policy.setWeekendMultiplier(weekendMultiplier);
        policy.setActive(true);
        return policyRepository.save(policy);
    }

    // ── Compute ──────────────────────────────────────────────────────────────────

    /**
     * (Re)compute overtime for every active employee for the given period from their attendance.
     * Idempotent: an existing DRAFT entry is updated in place (or removed if overtime drops to
     * zero); an already APPROVED/REJECTED entry is left untouched so a decision is never clobbered.
     */
    @Transactional
    public ComputeResult computeForPeriod(int month, int year) {
        UUID tenantId = currentTenantId();
        OvertimePolicy policy = getPolicyOrDefault();
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        OffsetDateTime now = OffsetDateTime.now();

        // Tenant's observed public holidays in the period — a day worked on one of these bills
        // entirely at the holiday multiplier (takes precedence over weekday/weekend).
        java.util.Set<LocalDate> holidays = publicHolidayService.activeDatesBetween(start, end);

        List<Employee> employees = employeeRepository.findByStatus(EmployeeStatus.ACTIVE);
        int scanned = 0, withOt = 0;

        for (Employee emp : employees) {
            scanned++;
            PayGrade grade = emp.getPayGrade();
            if (grade == null || grade.getGrossSalary() == null || grade.getGrossSalary() <= 0) {
                continue; // no salary basis → cannot rate overtime
            }

            List<AttendanceRecord> records = attendanceRepository
                    .findByEmployeeIdAndAttendanceDateBetween(emp.getId(), start, end, tenantId);

            BigDecimal weekdayHours = BigDecimal.ZERO;
            BigDecimal weekendHours = BigDecimal.ZERO;
            BigDecimal holidayHours = BigDecimal.ZERO;
            for (AttendanceRecord r : records) {
                BigDecimal worked = r.getHoursWorked();
                if (worked == null || worked.signum() <= 0) {
                    continue;
                }
                LocalDate date = r.getAttendanceDate();
                DayOfWeek dow = date.getDayOfWeek();
                if (holidays.contains(date)) {
                    holidayHours = holidayHours.add(worked); // all hours on a holiday are overtime
                } else if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                    weekendHours = weekendHours.add(worked); // all weekend hours are overtime
                } else {
                    BigDecimal extra = worked.subtract(policy.getStandardDailyHours());
                    if (extra.signum() > 0) {
                        weekdayHours = weekdayHours.add(extra);
                    }
                }
            }

            long hourlyRateKobo = BigDecimal.valueOf(grade.getGrossSalary())
                    .divide(BigDecimal.valueOf(policy.getStandardMonthlyHours()), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            BigDecimal rate = BigDecimal.valueOf(hourlyRateKobo);
            long amountKobo = weekdayHours.multiply(rate).multiply(policy.getWeekdayMultiplier())
                    .add(weekendHours.multiply(rate).multiply(policy.getWeekendMultiplier()))
                    .add(holidayHours.multiply(rate).multiply(policy.getHolidayMultiplier()))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            OvertimeEntry existing = entryRepository
                    .findByEmployeeIdAndPeriodYearAndPeriodMonth(emp.getId(), year, month)
                    .orElse(null);

            if (existing != null && existing.getStatus() != OvertimeStatus.DRAFT) {
                if (amountKobo > 0) {
                    withOt++;
                }
                continue; // preserve an already-decided entry
            }

            if (amountKobo <= 0) {
                if (existing != null) {
                    entryRepository.delete(existing); // stale draft, no overtime any more
                }
                continue;
            }

            OvertimeEntry entry = existing != null ? existing : OvertimeEntry.builder()
                    .tenantId(tenantId).employeeId(emp.getId())
                    .periodMonth(month).periodYear(year).build();
            entry.setWeekdayOtHours(weekdayHours.setScale(2, RoundingMode.HALF_UP));
            entry.setWeekendOtHours(weekendHours.setScale(2, RoundingMode.HALF_UP));
            entry.setHolidayOtHours(holidayHours.setScale(2, RoundingMode.HALF_UP));
            entry.setHourlyRateKobo(hourlyRateKobo);
            entry.setAmountKobo(amountKobo);
            entry.setStatus(OvertimeStatus.DRAFT);
            entry.setComputedAt(now);
            entryRepository.save(entry);
            withOt++;
        }

        log.info("Overtime compute {}/{}: scanned {}, {} with overtime", month, year, scanned, withOt);
        return new ComputeResult(scanned, withOt);
    }

    // ── Decisions ──────────────────────────────────────────────────────────────────

    /** Approve a DRAFT entry: emit a PENDING OVERTIME payroll adjustment and link it. */
    @Transactional
    public void approve(UUID entryId, String actor) {
        OvertimeEntry e = entryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Overtime entry not found."));
        if (e.getStatus() != OvertimeStatus.DRAFT) {
            throw new IllegalStateException("Only draft overtime can be approved.");
        }
        if (e.getAmountKobo() <= 0) {
            throw new IllegalStateException("This entry has no overtime to approve.");
        }
        String note = String.format("Overtime %d/%d: %sh weekday + %sh weekend + %sh holiday",
                e.getPeriodMonth(), e.getPeriodYear(),
                e.getWeekdayOtHours(), e.getWeekendOtHours(), e.getHolidayOtHours());
        UUID adjustmentId = payrollAdjustmentService.createSystemAdjustment(
                e.getEmployeeId(), "OVERTIME", e.getAmountKobo(),
                e.getPeriodMonth(), e.getPeriodYear(), note, actor);
        e.setStatus(OvertimeStatus.APPROVED);
        e.setPayrollAdjustmentId(adjustmentId);
        e.setApprovedBy(actor);
        e.setApprovedAt(OffsetDateTime.now());
        entryRepository.save(e);

        // Notify the employee that their overtime was approved and will hit the next payslip.
        notifyEmployeeApproved(e);
    }

    /** In-app notice to the employee that their overtime for the period was approved. */
    private void notifyEmployeeApproved(OvertimeEntry e) {
        employeeRepository.findById(e.getEmployeeId()).ifPresent(emp -> {
            if (emp.getEmail() == null || emp.getEmail().isBlank()) {
                return;
            }
            String period = java.time.Month.of(e.getPeriodMonth())
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + e.getPeriodYear();
            String message = String.format(
                    "Your %s overtime (%s) has been approved and will be added to your next payslip.",
                    period, formatNaira(e.getAmountKobo()));
            notificationService.createInAppNotification(
                    emp.getEmail(), "OVERTIME_APPROVED", "Overtime approved", message, "/htmx/payslips");
        });
    }

    /**
     * Reject an entry. Idempotent. Cancels the emitted adjustment if it is still PENDING; note that
     * if the payroll run already applied it, the pay stands until that run is reversed.
     */
    @Transactional
    public void reject(UUID entryId) {
        OvertimeEntry e = entryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Overtime entry not found."));
        if (e.getStatus() == OvertimeStatus.REJECTED) {
            return;
        }
        payrollAdjustmentService.cancelIfPending(e.getPayrollAdjustmentId());
        e.setStatus(OvertimeStatus.REJECTED);
        entryRepository.save(e);
    }

    // ── Page assembly ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OvertimePageView buildPageView(int month, int year) {
        OvertimePolicy policy = getPolicyOrDefault();
        List<OvertimeEntry> entries = entryRepository
                .findByPeriodYearAndPeriodMonthOrderByAmountKoboDesc(year, month);

        Map<UUID, Employee> employees = entries.isEmpty() ? Map.of()
                : employeeRepository.findAllById(entries.stream()
                        .map(OvertimeEntry::getEmployeeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Employee::getId, e -> e));

        int draftCount = 0, approvedCount = 0;
        long draftTotal = 0, approvedTotal = 0;
        List<OvertimePageView.EntryRow> rows = new java.util.ArrayList<>(entries.size());
        for (OvertimeEntry e : entries) {
            Employee emp = employees.get(e.getEmployeeId());
            String status = e.getStatus().name();
            if (e.getStatus() == OvertimeStatus.DRAFT) {
                draftCount++;
                draftTotal += e.getAmountKobo();
            } else if (e.getStatus() == OvertimeStatus.APPROVED) {
                approvedCount++;
                approvedTotal += e.getAmountKobo();
            }
            rows.add(new OvertimePageView.EntryRow(
                    e.getId(), e.getEmployeeId(),
                    emp != null ? emp.getFullName() : "—",
                    emp != null && emp.getJobTitle() != null ? emp.getJobTitle() : "—",
                    e.getWeekdayOtHours().toPlainString(),
                    e.getWeekendOtHours().toPlainString(),
                    e.getHolidayOtHours().toPlainString(),
                    formatNaira(e.getHourlyRateKobo()),
                    formatNaira(e.getAmountKobo()),
                    humanize(status),
                    statusKind(status),
                    e.getStatus() == OvertimeStatus.DRAFT));
        }

        String periodLabel = java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year;
        OvertimePageView.PolicyView policyView = new OvertimePageView.PolicyView(
                policy.getStandardDailyHours().toPlainString(),
                policy.getStandardMonthlyHours(),
                policy.getWeekdayMultiplier().toPlainString(),
                policy.getWeekendMultiplier().toPlainString());

        return new OvertimePageView(month, year, periodLabel, policyView, rows,
                draftCount, approvedCount, formatNaira(draftTotal), formatNaira(approvedTotal));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String formatNaira(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    private String statusKind(String status) {
        return switch (status) {
            case "APPROVED" -> "success";
            case "REJECTED" -> "error";
            default -> "warn"; // DRAFT
        };
    }

    private String humanize(String raw) {
        String lower = raw.toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
