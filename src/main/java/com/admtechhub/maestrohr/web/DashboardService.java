package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link DashboardView} for the redesigned dashboard.
 * All values are derived from real MaestroHR entities (employees, payroll, leave,
 * attendance) so the page renders fully populated with no client-side fetches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private static final DateTimeFormatter SHORT_DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final int BIRTHDAY_WINDOW_DAYS = 14;
    private static final int ANNIVERSARY_WINDOW_DAYS = 30;
    private static final int CELEBRATION_LIMIT = 5;

    /** Statuses that count as "showed up" for the daily attendance rate. */
    private static final Set<AttendanceStatus> PRESENT_STATUSES =
            EnumSet.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE, AttendanceStatus.HALF_DAY);

    private final EmployeeRepository employeeRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public DashboardView buildOverview() {
        UUID tenantId = currentTenantId();
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);

        List<Employee> employees =
                employeeRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent();

        long activeHeadcount = employeeRepository.countByStatus(EmployeeStatus.ACTIVE);
        long onLeaveCount = employeeRepository.countByStatus(EmployeeStatus.ON_LEAVE);
        long pendingLeaveCount =
                leaveRequestRepository.countByTenantIdAndStatus(tenantId, LeaveStatus.PENDING);
        long pendingPayrollApproval =
                payrollRunRepository.countByTenantIdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL);

        long newHiresThisMonth = employees.stream()
                .map(Employee::getEmploymentStartDate)
                .filter(d -> d != null && YearMonth.from(d).equals(thisMonth))
                .count();

        long leaveDaysThisMonth = leaveRequestRepository.sumApprovedLeaveDaysInRange(
                tenantId, thisMonth.atDay(1), thisMonth.atEndOfMonth());

        return new DashboardView(
                thisMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + thisMonth.getYear(),
                activeHeadcount,
                newHiresThisMonth,
                onLeaveCount,
                pendingLeaveCount,
                pendingPayrollApproval,
                buildPayrollSummary(tenantId, thisMonth),
                buildAttendanceToday(tenantId, today),
                leaveDaysThisMonth,
                buildBirthdays(employees, today),
                buildAnniversaries(employees, today)
        );
    }

    private DashboardView.PayrollSummary buildPayrollSummary(UUID tenantId, YearMonth month) {
        PayrollRun run = payrollRunRepository
                .findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(
                        tenantId, month.getMonthValue(), month.getYear())
                .orElseGet(() -> payrollRunRepository
                        .findTopByTenant_IdOrderByCreatedAtDesc(tenantId)
                        .orElse(null));

        if (run == null) {
            return new DashboardView.PayrollSummary(
                    false,
                    month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear(),
                    "₦0.00", "NONE", "No run yet", "neutral");
        }

        YearMonth runMonth = YearMonth.of(run.getPayrollYear(), run.getPayrollMonth());
        String monthLabel = runMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + runMonth.getYear();
        String amount = String.format(Locale.ENGLISH, "₦%,.2f", run.getTotalNet() / 100.0);
        String status = run.getStatus() != null ? run.getStatus().name() : "DRAFT";

        return new DashboardView.PayrollSummary(
                true, monthLabel, amount, status, humanize(status), payrollStatusKind(status));
    }

    /**
     * Today's attendance rate from the tenant's per-status day counts.
     * {@code present} = PRESENT + LATE + HALF_DAY; {@code total} excludes ON_LEAVE
     * (those staff aren't expected in). Returns an empty snapshot when no records exist.
     */
    private DashboardView.AttendanceToday buildAttendanceToday(UUID tenantId, LocalDate today) {
        List<Object[]> rows = attendanceRepository.countByStatusForDate(tenantId, today);
        long present = 0;
        long total = 0;
        for (Object[] row : rows) {
            AttendanceStatus status = (AttendanceStatus) row[0];
            long count = ((Number) row[1]).longValue();
            if (status == AttendanceStatus.ON_LEAVE) {
                continue; // not part of the expected roster
            }
            total += count;
            if (PRESENT_STATUSES.contains(status)) {
                present += count;
            }
        }
        if (total == 0) {
            return new DashboardView.AttendanceToday(false, 0, 0, 0);
        }
        int rate = (int) Math.round(present * 100.0 / total);
        return new DashboardView.AttendanceToday(true, present, total, rate);
    }

    private List<DashboardView.CelebrationItem> buildBirthdays(List<Employee> employees, LocalDate today) {
        List<DashboardView.CelebrationItem> result = new ArrayList<>();
        employees.stream()
                .filter(e -> e.getDateOfBirth() != null)
                .map(e -> {
                    LocalDate next = nextOccurrence(e.getDateOfBirth(), today);
                    long days = ChronoUnit.DAYS.between(today, next);
                    return new Object[]{e, next, days};
                })
                .filter(t -> (long) t[2] <= BIRTHDAY_WINDOW_DAYS)
                .sorted(Comparator.comparingLong(t -> (long) t[2]))
                .limit(CELEBRATION_LIMIT)
                .forEach(t -> {
                    Employee e = (Employee) t[0];
                    LocalDate next = (LocalDate) t[1];
                    long days = (long) t[2];
                    result.add(new DashboardView.CelebrationItem(
                            initials(e), e.getFullName(), departmentName(e),
                            relativeWhen(days, next), ""));
                });
        return result;
    }

    private List<DashboardView.CelebrationItem> buildAnniversaries(List<Employee> employees, LocalDate today) {
        List<DashboardView.CelebrationItem> result = new ArrayList<>();
        employees.stream()
                .filter(e -> e.getEmploymentStartDate() != null
                        && e.getEmploymentStartDate().isBefore(today))
                .map(e -> {
                    LocalDate next = nextOccurrence(e.getEmploymentStartDate(), today);
                    long days = ChronoUnit.DAYS.between(today, next);
                    int years = next.getYear() - e.getEmploymentStartDate().getYear();
                    return new Object[]{e, next, days, years};
                })
                .filter(t -> (long) t[2] <= ANNIVERSARY_WINDOW_DAYS && (int) t[3] >= 1)
                .sorted(Comparator.comparingLong(t -> (long) t[2]))
                .limit(CELEBRATION_LIMIT)
                .forEach(t -> {
                    Employee e = (Employee) t[0];
                    LocalDate next = (LocalDate) t[1];
                    int years = (int) t[3];
                    result.add(new DashboardView.CelebrationItem(
                            initials(e), e.getFullName(),
                            jobTitleOrDept(e) + " • " + next.format(SHORT_DATE_FMT),
                            "", years + "y"));
                });
        return result;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Next future (or today) anniversary of a month-day, rolling into next year if already passed. */
    private LocalDate nextOccurrence(LocalDate date, LocalDate today) {
        LocalDate candidate;
        try {
            candidate = date.withYear(today.getYear());
        } catch (Exception e) { // Feb 29 in a non-leap year
            candidate = date.withYear(today.getYear()).plusDays(1);
        }
        if (candidate.isBefore(today)) {
            candidate = candidate.plusYears(1);
        }
        return candidate;
    }

    private String relativeWhen(long days, LocalDate date) {
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        return date.format(SHORT_DATE_FMT);
    }

    private String initials(Employee e) {
        char a = e.getFirstName() != null && !e.getFirstName().isBlank() ? e.getFirstName().charAt(0) : '?';
        char b = e.getLastName() != null && !e.getLastName().isBlank() ? e.getLastName().charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
    }

    private String departmentName(Employee e) {
        return e.getDepartment() != null ? e.getDepartment().getName() : "—";
    }

    private String jobTitleOrDept(Employee e) {
        if (e.getJobTitle() != null && !e.getJobTitle().isBlank()) return e.getJobTitle();
        return departmentName(e);
    }

    private String payrollStatusKind(String status) {
        return switch (status) {
            case "PAID", "DISBURSED", "COMPLETED", "APPROVED" -> "success";
            case "FAILED", "CANCELLED" -> "error";
            case "DRAFT", "NONE" -> "neutral";
            default -> "warn"; // PENDING_APPROVAL, DISBURSING, etc.
        };
    }

    /** Turn "PENDING_APPROVAL" / "create_employee" into "Pending Approval" / "Create Employee". */
    private String humanize(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        String spaced = raw.replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ENGLISH);
        StringBuilder sb = new StringBuilder(spaced.length());
        boolean cap = true;
        for (char c : spaced.toCharArray()) {
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
