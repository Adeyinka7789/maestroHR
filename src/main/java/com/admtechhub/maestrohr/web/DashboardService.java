package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.leave.LeaveBalanceRepository;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.payment.Invoice;
import com.admtechhub.maestrohr.payment.InvoiceRepository;
import com.admtechhub.maestrohr.payment.PaymentStatus;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
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
 * The dashboard is role-aware: {@link #buildOverview()} reads the authenticated user's
 * role and populates only the section relevant to them, so a single page serves every
 * role. HR_ADMIN / SUPER_ADMIN get the company-wide overview (the top-level fields);
 * EMPLOYEE / FINANCE_OFFICER / DEPT_MANAGER each get a dedicated nullable section.
 *
 * All values are derived from real MaestroHR entities (employees, payroll, leave,
 * attendance, billing) so the page renders fully populated with no client-side fetches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private static final DateTimeFormatter SHORT_DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final int BIRTHDAY_WINDOW_DAYS = 14;
    private static final int ANNIVERSARY_WINDOW_DAYS = 30;
    private static final int CELEBRATION_LIMIT = 5;

    /** Statuses that count as "showed up" for the daily attendance rate. */
    private static final Set<AttendanceStatus> PRESENT_STATUSES =
            EnumSet.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE, AttendanceStatus.HALF_DAY);

    /**
     * Payroll runs that are final enough to surface a downloadable payslip / count as
     * "current payroll cost" — approved and beyond. DRAFT / PENDING_APPROVAL aren't final,
     * REJECTED never paid out.
     */
    private static final Set<PayrollStatus> APPROVED_OR_LATER =
            EnumSet.of(PayrollStatus.APPROVED, PayrollStatus.DISBURSING, PayrollStatus.COMPLETED);

    private final EmployeeRepository employeeRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final AttendanceRepository attendanceRepository;
    private final InvoiceRepository invoiceRepository;

    @Transactional(readOnly = true)
    public DashboardView buildOverview() {
        UUID tenantId = currentTenantId();
        UserRole role = currentRole();
        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);
        String periodLabel = monthLabel(thisMonth);

        // Top-level fields back the HR_ADMIN company overview; they stay at their empty
        // defaults for the other roles (whose th:if blocks don't read them).
        long activeHeadcount = 0;
        long newHiresThisMonth = 0;
        long onLeaveCount = 0;
        long pendingLeaveCount = 0;
        long pendingPayrollApproval = 0;
        long leaveDaysThisMonth = 0;
        DashboardView.PayrollSummary payroll = emptyPayrollSummary(thisMonth);
        DashboardView.AttendanceToday attendanceToday = emptyAttendance();
        List<DashboardView.CelebrationItem> birthdays = List.of();
        List<DashboardView.CelebrationItem> anniversaries = List.of();

        DashboardView.EmployeeSection employeeSection = null;
        DashboardView.FinanceSection financeSection = null;
        DashboardView.DeptManagerSection deptSection = null;

        switch (role) {
            case EMPLOYEE -> employeeSection = buildEmployeeSection(tenantId, today);
            case FINANCE_OFFICER -> financeSection = buildFinanceSection(tenantId);
            case DEPT_MANAGER -> deptSection = buildDeptManagerSection(tenantId, today);
            default -> {
                // HR_ADMIN, SUPER_ADMIN (and any unrecognized role) → the full company overview.
                List<Employee> employees =
                        employeeRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent();
                activeHeadcount = employeeRepository.countByStatus(EmployeeStatus.ACTIVE);
                onLeaveCount = employeeRepository.countByStatus(EmployeeStatus.ON_LEAVE);
                pendingLeaveCount =
                        leaveRequestRepository.countByTenantIdAndStatus(tenantId, LeaveStatus.PENDING);
                pendingPayrollApproval =
                        payrollRunRepository.countByTenantIdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL);
                newHiresThisMonth = countNewHires(employees, thisMonth);
                leaveDaysThisMonth = leaveRequestRepository.sumApprovedLeaveDaysInRange(
                        tenantId, thisMonth.atDay(1), thisMonth.atEndOfMonth());
                payroll = buildPayrollSummary(tenantId, thisMonth);
                attendanceToday = buildAttendanceToday(tenantId, today);
                birthdays = buildBirthdays(employees, today);
                anniversaries = buildAnniversaries(employees, today);
            }
        }

        return new DashboardView(
                periodLabel,
                role.name(),
                activeHeadcount,
                newHiresThisMonth,
                onLeaveCount,
                pendingLeaveCount,
                pendingPayrollApproval,
                payroll,
                attendanceToday,
                leaveDaysThisMonth,
                birthdays,
                anniversaries,
                employeeSection,
                financeSection,
                deptSection
        );
    }

    // ── EMPLOYEE ────────────────────────────────────────────────────────────────

    private DashboardView.EmployeeSection buildEmployeeSection(UUID tenantId, LocalDate today) {
        // Celebrations are company-wide, shown to employees too.
        List<Employee> employees =
                employeeRepository.findAllByTenantId(tenantId, Pageable.unpaged()).getContent();
        List<DashboardView.CelebrationItem> birthdays = buildBirthdays(employees, today);
        List<DashboardView.CelebrationItem> anniversaries = buildAnniversaries(employees, today);

        Employee me = employeeRepository.findByEmail(currentEmail()).orElse(null);
        if (me == null) {
            // Authenticated account with no Employee record — show celebrations only.
            return new DashboardView.EmployeeSection(
                    false, null, List.of(),
                    today.format(SHORT_DATE_FMT), "No profile", "neutral", "—", "—",
                    null, 0, List.of(), birthdays, anniversaries);
        }

        List<DashboardView.LeaveBalanceItem> balances =
                leaveBalanceRepository.findByEmployeeIdAndYear(me.getId(), today.getYear()).stream()
                        .map(b -> new DashboardView.LeaveBalanceItem(
                                b.getLeaveType() != null ? b.getLeaveType().getName() : "—",
                                nz(b.getTotalDaysEntitled()),
                                nz(b.getDaysTaken()),
                                nz(b.getDaysRemaining())))
                        .sorted(Comparator.comparing(DashboardView.LeaveBalanceItem::typeName,
                                String.CASE_INSENSITIVE_ORDER))
                        .toList();

        AttendanceRecord record =
                attendanceRepository.findByEmployeeIdAndAttendanceDate(me.getId(), today).orElse(null);
        boolean checkedIn = record != null && record.getClockInTime() != null;
        boolean checkedOut = record != null && record.getClockOutTime() != null;
        String attStatusLabel = checkedOut ? "Checked out"
                : (checkedIn ? "Checked in" : "Not checked in yet");
        String attStatusKind = checkedIn && !checkedOut ? "success" : "neutral";

        DashboardView.PayslipItem latestPayslip = payrollEntryRepository
                .findByEmployeeIdOrderByPayrollRunCreatedAtDesc(me.getId()).stream()
                .filter(e -> e.getPayrollRun() != null
                        && APPROVED_OR_LATER.contains(e.getPayrollRun().getStatus()))
                .findFirst()
                .map(this::toPayslipItem)
                .orElse(null);

        List<DashboardView.MyLeaveItem> pending =
                leaveRequestRepository.findByEmployeeIdAndStatus(me.getId(), LeaveStatus.PENDING).stream()
                        .sorted(Comparator.comparing(LeaveRequest::getStartDate))
                        .map(this::toMyLeaveItem)
                        .toList();

        return new DashboardView.EmployeeSection(
                true,
                me.getFullName(),
                balances,
                today.format(SHORT_DATE_FMT),
                attStatusLabel,
                attStatusKind,
                formatTime(record == null ? null : record.getClockInTime()),
                formatTime(record == null ? null : record.getClockOutTime()),
                latestPayslip,
                pending.size(),
                pending,
                birthdays,
                anniversaries);
    }

    private DashboardView.PayslipItem toPayslipItem(PayrollEntry entry) {
        PayrollRun run = entry.getPayrollRun();
        String statusLabel;
        String statusKind;
        switch (run.getStatus()) {
            case COMPLETED -> { statusLabel = "Paid"; statusKind = "success"; }
            case DISBURSING -> { statusLabel = "Disbursing"; statusKind = "warn"; }
            default -> { statusLabel = "Approved"; statusKind = "neutral"; } // APPROVED
        }
        return new DashboardView.PayslipItem(
                run.getId(),
                monthLabel(YearMonth.of(run.getPayrollYear(), run.getPayrollMonth())),
                formatNaira(entry.getNetSalary()),
                statusLabel,
                statusKind);
    }

    private DashboardView.MyLeaveItem toMyLeaveItem(LeaveRequest req) {
        String range = req.getStartDate().format(SHORT_DATE_FMT) + " – " + req.getEndDate().format(SHORT_DATE_FMT);
        return new DashboardView.MyLeaveItem(
                req.getLeaveType() != null ? req.getLeaveType().getName() : "—",
                range,
                nz(req.getDaysRequested()),
                "Pending",
                "warn");
    }

    // ── FINANCE_OFFICER ───────────────────────────────────────────────────────────

    private DashboardView.FinanceSection buildFinanceSection(UUID tenantId) {
        long pendingPayrollApproval =
                payrollRunRepository.countByTenantIdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL);

        // Current payroll cost = net total of the most recent approved-or-later run.
        PayrollRun latestApproved = payrollRunRepository
                .findAllByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .filter(r -> APPROVED_OR_LATER.contains(r.getStatus()))
                .findFirst()
                .orElse(null);
        boolean hasCost = latestApproved != null;
        String costFormatted = hasCost ? formatNaira(latestApproved.getTotalNet()) : "₦0.00";
        String costPeriod = hasCost
                ? monthLabel(YearMonth.of(latestApproved.getPayrollYear(), latestApproved.getPayrollMonth()))
                : "No approved run yet";

        List<DashboardView.InvoiceItem> recentInvoices =
                invoiceRepository.findTop5ByOrderByCreatedAtDesc().stream()
                        .map(this::toInvoiceItem)
                        .toList();

        return new DashboardView.FinanceSection(
                pendingPayrollApproval, hasCost, costFormatted, costPeriod, recentInvoices);
    }

    private DashboardView.InvoiceItem toInvoiceItem(Invoice inv) {
        PaymentStatus status = inv.getStatus();
        String statusLabel;
        String statusKind;
        if (status == PaymentStatus.SUCCESS) {
            statusLabel = "Success"; statusKind = "success";
        } else if (status == PaymentStatus.FAILED) {
            statusLabel = "Failed"; statusKind = "error";
        } else {
            statusLabel = "Pending"; statusKind = "warn";
        }
        return new DashboardView.InvoiceItem(
                inv.getPaystackReference(),
                formatNaira(inv.getAmountKobo()),
                inv.getPlan() != null ? humanize(inv.getPlan().name()) : "—",
                statusLabel,
                statusKind,
                inv.getCreatedAt() != null ? inv.getCreatedAt().format(SHORT_DATE_FMT) : "—");
    }

    // ── DEPT_MANAGER ──────────────────────────────────────────────────────────────

    private DashboardView.DeptManagerSection buildDeptManagerSection(UUID tenantId, LocalDate today) {
        Employee me = employeeRepository.findByEmail(currentEmail()).orElse(null);
        Department dept = me != null ? me.getDepartment() : null;
        if (dept == null) {
            return new DashboardView.DeptManagerSection(
                    false, null, 0, 0, 0, 0, emptyAttendance(), "₦0");
        }

        UUID deptId = dept.getId();
        List<Employee> members = employeeRepository.findByDepartmentId(deptId, PageRequest.of(0, 20));
        long headcount = members.size();
        long active = members.stream().filter(e -> e.getStatus() == EmployeeStatus.ACTIVE).count();
        long onLeave = members.stream().filter(e -> e.getStatus() == EmployeeStatus.ON_LEAVE).count();
        long pendingLeave =
                leaveRequestRepository.countByDepartmentIdAndStatus(deptId, LeaveStatus.PENDING);
        DashboardView.AttendanceToday attendance = attendanceFromRows(
                attendanceRepository.countByStatusForDateAndDepartment(tenantId, deptId, today));

        // Monthly establishment cost: pay-grade gross salary over ACTIVE members only (kobo),
        // matching DepartmentDetailService.
        long payrollKobo = members.stream()
                .filter(e -> e.getStatus() == EmployeeStatus.ACTIVE)
                .map(Employee::getPayGrade)
                .filter(java.util.Objects::nonNull)
                .mapToLong(pg -> pg.getGrossSalary() != null ? pg.getGrossSalary() : 0L)
                .sum();

        return new DashboardView.DeptManagerSection(
                true,
                dept.getName(),
                headcount,
                active,
                onLeave,
                pendingLeave,
                attendance,
                formatNairaWhole(payrollKobo));
    }

    // ── HR_ADMIN building blocks (unchanged behaviour) ──────────────────────────────

    private long countNewHires(List<Employee> employees, YearMonth thisMonth) {
        return employees.stream()
                .map(Employee::getEmploymentStartDate)
                .filter(d -> d != null && YearMonth.from(d).equals(thisMonth))
                .count();
    }

    private DashboardView.PayrollSummary buildPayrollSummary(UUID tenantId, YearMonth month) {
        PayrollRun run = payrollRunRepository
                .findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(
                        tenantId, month.getMonthValue(), month.getYear())
                .orElseGet(() -> payrollRunRepository
                        .findTopByTenant_IdOrderByCreatedAtDesc(tenantId)
                        .orElse(null));

        if (run == null) {
            return emptyPayrollSummary(month);
        }

        YearMonth runMonth = YearMonth.of(run.getPayrollYear(), run.getPayrollMonth());
        String monthLabel = monthLabel(runMonth);
        String amount = formatNaira(run.getTotalNet());
        String status = run.getStatus() != null ? run.getStatus().name() : "DRAFT";

        return new DashboardView.PayrollSummary(
                true, monthLabel, amount, status, humanize(status), payrollStatusKind(status));
    }

    private DashboardView.PayrollSummary emptyPayrollSummary(YearMonth month) {
        return new DashboardView.PayrollSummary(
                false, monthLabel(month), "₦0.00", "NONE", "No run yet", "neutral");
    }

    /**
     * Today's attendance rate from the tenant's per-status day counts.
     * {@code present} = PRESENT + LATE + HALF_DAY; {@code total} excludes ON_LEAVE
     * (those staff aren't expected in). Returns an empty snapshot when no records exist.
     */
    private DashboardView.AttendanceToday buildAttendanceToday(UUID tenantId, LocalDate today) {
        return attendanceFromRows(attendanceRepository.countByStatusForDate(tenantId, today));
    }

    /** Roll up [status, count] rows into an {@link DashboardView.AttendanceToday} snapshot. */
    private DashboardView.AttendanceToday attendanceFromRows(List<Object[]> rows) {
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
            return emptyAttendance();
        }
        int rate = (int) Math.round(present * 100.0 / total);
        return new DashboardView.AttendanceToday(true, present, total, rate);
    }

    private DashboardView.AttendanceToday emptyAttendance() {
        return new DashboardView.AttendanceToday(false, 0, 0, 0);
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

    private String monthLabel(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
    }

    /** Kobo → "₦42,500,000.00". Used for payroll net and invoice amounts. */
    private String formatNaira(Long amountInKobo) {
        double naira = amountInKobo == null ? 0.0 : amountInKobo / 100.0;
        return String.format(Locale.ENGLISH, "₦%,.2f", naira);
    }

    /** Kobo → whole-naira "₦42,500,000" (no decimals). Used for establishment cost. */
    private String formatNairaWhole(long amountInKobo) {
        return String.format(Locale.ENGLISH, "₦%,d", amountInKobo / 100);
    }

    private String formatTime(LocalTime time) {
        return time == null ? "—" : time.format(TIME_FMT);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
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

    /**
     * The authenticated user's role, parsed from the {@code ROLE_*} granted authority.
     * Defaults to {@link UserRole#HR_ADMIN} when no role can be resolved, preserving the
     * prior behaviour where the dashboard always rendered the company overview.
     */
    private UserRole currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority authority : auth.getAuthorities()) {
                String name = authority.getAuthority();
                if (name != null && name.startsWith("ROLE_")) {
                    try {
                        return UserRole.valueOf(name.substring("ROLE_".length()));
                    } catch (IllegalArgumentException ignored) {
                        // Not a known UserRole — keep scanning.
                    }
                }
            }
        }
        return UserRole.HR_ADMIN;
    }

    private String currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
