package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link AttendanceAnalyticsView} for the attendance Analytics
 * tab — the company-wide, whole-team counterpart to the per-employee
 * {@link AttendanceCalendarService}. Supports four period types (day / week / month / custom
 * range); see {@link AttendanceAnalyticsView} for the period model and the single
 * attendance-rate definition used across every card, table, and trend bar.
 *
 * Grouped range queries back the whole page: a per-employee×status grouping (the employee
 * table, re-aggregated by department for the breakdown and summed for the summary cards) and a
 * per-day×status grouping (the trend + active-day count). A department filter is applied at the
 * query level for the trend and in-memory for the per-employee aggregate, so every figure
 * reflects the same scope. No client-side fetches.
 *
 * SCOPE: read-only. No write actions.
 */
@Service
@RequiredArgsConstructor
public class AttendanceAnalyticsService {

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter D_MON = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter D_MON_Y = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TOOLTIP_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private static final String UNASSIGNED = "Unassigned";

    /** Above this many days the trend switches from per-day bars to 7-day buckets (avoids a 300-bar strip). */
    private static final int DAILY_TREND_MAX_DAYS = 40;

    /** Page size for the employee reference sweep (matches the Excel export's paging). */
    private static final int EMPLOYEE_PAGE = 500;

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public AttendanceAnalyticsView build(String period, String date, String from, String to, UUID departmentId) {
        UUID tenantId = currentTenantId();
        Window window = resolveWindow(period, date, from, to);
        LocalDate start = window.start();
        LocalDate end = window.end();

        // Employee reference data: id → (name, number, department). Backs the table labels,
        // the department breakdown/filter, and the in-memory department scoping below.
        Map<UUID, EmployeeMeta> employees = loadEmployees(tenantId);
        List<AttendanceAnalyticsView.DeptOption> deptOptions = buildDeptOptions(employees);
        String selectedDept = departmentId == null ? null : departmentId.toString();
        Set<UUID> inScope = departmentId == null ? null : scopedEmployeeIds(employees, departmentId);

        // Per-employee × status (department-scoped in-memory) → employee rows, dept rows, summary.
        Map<UUID, Counts> perEmployee = aggregatePerEmployee(
                attendanceRepository.countByEmployeeAndStatusForRange(tenantId, start, end), inScope);

        List<AttendanceAnalyticsView.EmployeeRow> employeeRows = buildEmployeeRows(perEmployee, employees);
        List<AttendanceAnalyticsView.DepartmentRow> departmentRows = buildDepartmentRows(perEmployee, employees);

        // Per-day × status (department-scoped at the query level) → trend + active-day count.
        List<Object[]> dayRows = departmentId == null
                ? attendanceRepository.countByDateAndStatusForRange(tenantId, start, end)
                : attendanceRepository.countByDateAndStatusForRangeAndDepartment(tenantId, departmentId, start, end);
        Map<LocalDate, Counts> byDate = aggregatePerDay(dayRows);

        long spanDays = ChronoUnit.DAYS.between(start, end) + 1;
        boolean showTrend = spanDays > 1;
        boolean weeklyTrend = spanDays > DAILY_TREND_MAX_DAYS;
        List<AttendanceAnalyticsView.TrendPoint> trend = !showTrend ? List.of()
                : (weeklyTrend ? buildWeeklyTrend(byDate, start, end) : buildDailyTrend(byDate, start, end));
        int trendMaxRate = trend.stream().mapToInt(AttendanceAnalyticsView.TrendPoint::barHeightPct).max().orElse(0);

        AttendanceAnalyticsView.Summary summary = buildSummary(perEmployee, byDate.size(), spanDays);

        Nav nav = buildNav(window);
        return new AttendanceAnalyticsView(
                window.period(), window.custom(),
                start.toString(), end.toString(), buildLabel(window),
                nav.prevDate(), nav.nextDate(),
                nav.prevFrom(), nav.prevTo(), nav.nextFrom(), nav.nextTo(),
                deptOptions, selectedDept,
                summary, employeeRows, departmentRows,
                trend, showTrend, weeklyTrend, trendMaxRate);
    }

    // ── period window resolution ─────────────────────────────────────────────────────

    private record Window(String period, boolean custom, LocalDate start, LocalDate end, LocalDate anchor) {}

    private record Nav(String prevDate, String nextDate,
                       String prevFrom, String prevTo, String nextFrom, String nextTo) {}

    /**
     * Resolve the requested period to a concrete [start, end] window. Absent/invalid input falls
     * back to the current month (mirrors the module's lenient param parsing). For presets the
     * anchor is a date inside the window (drives prev/next); for custom the window is from/to.
     */
    private Window resolveWindow(String period, String date, String from, String to) {
        String p = period == null ? "" : period.trim().toLowerCase(Locale.ENGLISH);
        LocalDate today = LocalDate.now();
        switch (p) {
            case "day" -> {
                LocalDate d = parse(date, today);
                return new Window("day", false, d, d, d);
            }
            case "week" -> {
                LocalDate d = parse(date, today);
                LocalDate monday = d.with(DayOfWeek.MONDAY);
                return new Window("week", false, monday, monday.plusDays(6), monday);
            }
            case "custom" -> {
                LocalDate s = parse(from, YearMonth.now().atDay(1));
                LocalDate e = parse(to, YearMonth.now().atEndOfMonth());
                if (e.isBefore(s)) {
                    e = s;
                }
                return new Window("custom", true, s, e, s);
            }
            default -> {
                LocalDate d = parse(date, today); // "month" and anything unknown → month
                YearMonth ym = YearMonth.from(d);
                return new Window("month", false, ym.atDay(1), ym.atEndOfMonth(), ym.atDay(1));
            }
        }
    }

    /** Compute the prev/next navigation targets for the window (a preset anchor, or a slid custom window). */
    private Nav buildNav(Window w) {
        if (w.custom()) {
            long span = ChronoUnit.DAYS.between(w.start(), w.end()) + 1;
            LocalDate prevTo = w.start().minusDays(1);
            LocalDate prevFrom = prevTo.minusDays(span - 1);
            LocalDate nextFrom = w.end().plusDays(1);
            LocalDate nextTo = nextFrom.plusDays(span - 1);
            return new Nav(null, null,
                    prevFrom.toString(), prevTo.toString(), nextFrom.toString(), nextTo.toString());
        }
        LocalDate anchor = w.anchor();
        LocalDate prev;
        LocalDate next;
        switch (w.period()) {
            case "day" -> {
                prev = anchor.minusDays(1);
                next = anchor.plusDays(1);
            }
            case "week" -> {
                prev = anchor.minusWeeks(1);
                next = anchor.plusWeeks(1);
            }
            default -> { // month
                prev = anchor.minusMonths(1);
                next = anchor.plusMonths(1);
            }
        }
        return new Nav(prev.toString(), next.toString(), null, null, null, null);
    }

    private String buildLabel(Window w) {
        if ("month".equals(w.period())) {
            return YearMonth.from(w.start()).format(MONTH_LABEL);
        }
        if (w.start().equals(w.end())) {
            return w.start().format(DAY_LABEL);
        }
        if (w.start().getYear() == w.end().getYear()) {
            return w.start().format(D_MON) + " – " + w.end().format(D_MON_Y);
        }
        return w.start().format(D_MON_Y) + " – " + w.end().format(D_MON_Y);
    }

    private LocalDate parse(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    // ── employee reference data ──────────────────────────────────────────────────────

    private record EmployeeMeta(String name, String number, UUID departmentId, String departmentName) {}

    private Map<UUID, EmployeeMeta> loadEmployees(UUID tenantId) {
        Map<UUID, EmployeeMeta> map = new HashMap<>();
        int page = 0;
        Page<Employee> result;
        do {
            result = employeeRepository.findAllByTenantId(
                    tenantId, PageRequest.of(page, EMPLOYEE_PAGE, Sort.by("firstName", "lastName")));
            for (Employee e : result.getContent()) {
                UUID deptId = e.getDepartment() != null ? e.getDepartment().getId() : null;
                String deptName = e.getDepartment() != null ? e.getDepartment().getName() : null;
                String number = e.getEmployeeNumber() == null ? "" : e.getEmployeeNumber();
                map.put(e.getId(), new EmployeeMeta(e.getFullName(), number, deptId, deptName));
            }
            page++;
        } while (result.hasNext());
        return map;
    }

    /** Distinct departments among the tenant's employees, name-sorted, for the filter dropdown. */
    private List<AttendanceAnalyticsView.DeptOption> buildDeptOptions(Map<UUID, EmployeeMeta> employees) {
        Map<UUID, String> byId = new HashMap<>();
        for (EmployeeMeta m : employees.values()) {
            if (m.departmentId() != null) {
                byId.put(m.departmentId(), m.departmentName());
            }
        }
        return byId.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue() == null ? "" : e.getValue(), String.CASE_INSENSITIVE_ORDER))
                .map(e -> new AttendanceAnalyticsView.DeptOption(e.getKey().toString(), e.getValue()))
                .toList();
    }

    private Set<UUID> scopedEmployeeIds(Map<UUID, EmployeeMeta> employees, UUID departmentId) {
        Set<UUID> ids = new HashSet<>();
        for (Map.Entry<UUID, EmployeeMeta> e : employees.entrySet()) {
            if (departmentId.equals(e.getValue().departmentId())) {
                ids.add(e.getKey());
            }
        }
        return ids;
    }

    // ── aggregation ──────────────────────────────────────────────────────────────────

    /** [employeeId, status, count] rows → employeeId → Counts, honouring the dept scope. */
    private Map<UUID, Counts> aggregatePerEmployee(List<Object[]> rows, Set<UUID> inScope) {
        Map<UUID, Counts> map = new HashMap<>();
        for (Object[] row : rows) {
            UUID empId = (UUID) row[0];
            if (inScope != null && !inScope.contains(empId)) {
                continue;
            }
            AttendanceStatus status = (AttendanceStatus) row[1];
            long count = (Long) row[2];
            map.computeIfAbsent(empId, k -> new Counts()).addStatus(status, count);
        }
        return map;
    }

    /** [date, status, count] rows → date → Counts. */
    private Map<LocalDate, Counts> aggregatePerDay(List<Object[]> rows) {
        Map<LocalDate, Counts> map = new TreeMap<>();
        for (Object[] row : rows) {
            LocalDate date = (LocalDate) row[0];
            AttendanceStatus status = (AttendanceStatus) row[1];
            long count = (Long) row[2];
            map.computeIfAbsent(date, k -> new Counts()).addStatus(status, count);
        }
        return map;
    }

    private List<AttendanceAnalyticsView.EmployeeRow> buildEmployeeRows(
            Map<UUID, Counts> perEmployee, Map<UUID, EmployeeMeta> employees) {

        List<AttendanceAnalyticsView.EmployeeRow> rows = new ArrayList<>(perEmployee.size());
        for (Map.Entry<UUID, Counts> e : perEmployee.entrySet()) {
            EmployeeMeta meta = employees.get(e.getKey());
            String name = meta != null ? meta.name() : "Unknown";
            String number = meta != null ? meta.number() : "";
            String dept = meta != null && meta.departmentName() != null ? meta.departmentName() : "—";
            Counts c = e.getValue();
            Integer rate = c.rate();
            rows.add(new AttendanceAnalyticsView.EmployeeRow(
                    e.getKey(), name, number, dept,
                    c.present, c.late, c.absent, c.halfDay, c.onLeave, rate, rateKind(rate)));
        }
        // Lowest attendance first — the rows HR most wants to act on float to the top; nulls
        // (no working records, e.g. a range of only leave) sort last, then alphabetical by name.
        rows.sort(Comparator
                .comparing((AttendanceAnalyticsView.EmployeeRow r) -> r.rate() == null ? Integer.MAX_VALUE : r.rate())
                .thenComparing(AttendanceAnalyticsView.EmployeeRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private List<AttendanceAnalyticsView.DepartmentRow> buildDepartmentRows(
            Map<UUID, Counts> perEmployee, Map<UUID, EmployeeMeta> employees) {

        Map<String, Counts> byDept = new LinkedHashMap<>();
        Map<String, Set<UUID>> heads = new HashMap<>();
        for (Map.Entry<UUID, Counts> e : perEmployee.entrySet()) {
            EmployeeMeta meta = employees.get(e.getKey());
            String dept = meta != null && meta.departmentName() != null ? meta.departmentName() : UNASSIGNED;
            byDept.computeIfAbsent(dept, k -> new Counts()).add(e.getValue());
            heads.computeIfAbsent(dept, k -> new HashSet<>()).add(e.getKey());
        }

        List<AttendanceAnalyticsView.DepartmentRow> rows = new ArrayList<>(byDept.size());
        for (Map.Entry<String, Counts> e : byDept.entrySet()) {
            Counts c = e.getValue();
            Integer rate = c.rate();
            rows.add(new AttendanceAnalyticsView.DepartmentRow(
                    e.getKey(), heads.get(e.getKey()).size(),
                    c.present, c.late, c.absent, c.halfDay, c.onLeave, rate, rateKind(rate)));
        }
        rows.sort(Comparator.comparing(AttendanceAnalyticsView.DepartmentRow::department, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private AttendanceAnalyticsView.Summary buildSummary(
            Map<UUID, Counts> perEmployee, int activeDays, long spanDays) {

        Counts total = new Counts();
        for (Counts c : perEmployee.values()) {
            total.add(c);
        }
        Integer rate = total.rate();
        return new AttendanceAnalyticsView.Summary(
                total.present, total.late, total.absent, total.halfDay, total.onLeave,
                total.total(), total.working(), rate, rateKind(rate),
                perEmployee.size(), activeDays, (int) spanDays);
    }

    // ── trend ────────────────────────────────────────────────────────────────────────

    private List<AttendanceAnalyticsView.TrendPoint> buildDailyTrend(
            Map<LocalDate, Counts> byDate, LocalDate start, LocalDate end) {

        LocalDate today = LocalDate.now();
        List<AttendanceAnalyticsView.TrendPoint> points = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Counts c = byDate.getOrDefault(d, Counts.EMPTY);
            Integer rate = c.rate();
            boolean weekend = switch (d.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> true;
                default -> false;
            };
            String tooltip = d.format(TOOLTIP_DATE) + " — "
                    + (rate == null ? "no records" : rate + "%")
                    + " (P" + c.present + " L" + c.late + " A" + c.absent + " H" + c.halfDay + ")";
            points.add(point(d, String.valueOf(d.getDayOfMonth()), c, rate, weekend, d.equals(today), tooltip));
        }
        return points;
    }

    private List<AttendanceAnalyticsView.TrendPoint> buildWeeklyTrend(
            Map<LocalDate, Counts> byDate, LocalDate start, LocalDate end) {

        LocalDate today = LocalDate.now();
        List<AttendanceAnalyticsView.TrendPoint> points = new ArrayList<>();
        for (LocalDate bucketStart = start; !bucketStart.isAfter(end); bucketStart = bucketStart.plusDays(7)) {
            LocalDate bucketEnd = bucketStart.plusDays(6).isAfter(end) ? end : bucketStart.plusDays(6);
            Counts c = new Counts();
            for (LocalDate d = bucketStart; !d.isAfter(bucketEnd); d = d.plusDays(1)) {
                Counts day = byDate.get(d);
                if (day != null) {
                    c.add(day);
                }
            }
            Integer rate = c.rate();
            boolean coversToday = !today.isBefore(bucketStart) && !today.isAfter(bucketEnd);
            String tooltip = "Week of " + bucketStart.format(TOOLTIP_DATE) + " — "
                    + (rate == null ? "no records" : rate + "%")
                    + " (P" + c.present + " L" + c.late + " A" + c.absent + " H" + c.halfDay + ")";
            points.add(point(bucketStart, bucketStart.format(TOOLTIP_DATE), c, rate, false, coversToday, tooltip));
        }
        return points;
    }

    private AttendanceAnalyticsView.TrendPoint point(
            LocalDate date, String label, Counts c, Integer rate, boolean weekend, boolean today, String tooltip) {
        int bar = rate == null ? 0 : Math.max(0, Math.min(100, rate));
        return new AttendanceAnalyticsView.TrendPoint(
                date.toString(), label, c.present, c.late, c.absent, c.halfDay, c.onLeave,
                rate, bar, weekend, today, tooltip);
    }

    // ── counts helper (the rate definition lives here, once) ─────────────────────────

    /**
     * Mutable accumulator for the five statuses plus the derived rate. The attendance-rate
     * definition (attended / working, ON_LEAVE excluded) is implemented once in {@link #rate()}
     * so cards, employee rows, department rows, and trend bars can never drift apart.
     */
    private static final class Counts {
        /** Shared empty instance for gap days in the trend; never mutated (read-only path). */
        static final Counts EMPTY = new Counts();

        long present, late, absent, halfDay, onLeave;

        void add(Counts src) {
            present += src.present;
            late += src.late;
            absent += src.absent;
            halfDay += src.halfDay;
            onLeave += src.onLeave;
        }

        void addStatus(AttendanceStatus status, long count) {
            switch (status) {
                case PRESENT -> present += count;
                case LATE -> late += count;
                case ABSENT -> absent += count;
                case HALF_DAY -> halfDay += count;
                case ON_LEAVE -> onLeave += count;
            }
        }

        long total() {
            return present + late + absent + halfDay + onLeave;
        }

        long working() {
            return present + late + absent + halfDay;
        }

        Integer rate() {
            long working = working();
            if (working == 0) {
                return null;
            }
            long attended = present + late + halfDay;
            return (int) Math.round(attended * 100.0 / working);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Rate → colour bucket for card accents and table pills. Null (no data) → neutral. */
    private String rateKind(Integer rate) {
        if (rate == null) {
            return "neutral";
        }
        if (rate >= 90) {
            return "success";
        }
        if (rate >= 75) {
            return "warn";
        }
        return "error";
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
