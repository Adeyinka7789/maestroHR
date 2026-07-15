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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
 * Assembles the server-rendered {@link AttendanceAnalyticsView} for the attendance
 * Analytics tab — the company-wide, whole-team counterpart to the per-employee
 * {@link AttendanceCalendarService}. Grouped range queries back the whole page:
 * a per-employee×status grouping (the employee table, re-aggregated by department for the
 * breakdown and summed for the summary cards) and a per-day×status grouping (the trend and
 * the active-day count). A tenant employee lookup supplies names/numbers/departments and the
 * department filter options. No client-side fetches.
 *
 * A department filter is applied at the query level for the trend
 * ({@code countByDateAndStatusForRangeAndDepartment}) and in-memory for the per-employee
 * aggregate (already keyed by employee), so every card, table, and bar reflects the same
 * scope. See {@link AttendanceAnalyticsView} for the single attendance-rate definition used
 * across the tab.
 *
 * SCOPE: read-only. No write actions.
 */
@Service
@RequiredArgsConstructor
public class AttendanceAnalyticsService {

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TOOLTIP_DATE =
            DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

    private static final String UNASSIGNED = "Unassigned";

    /** Page size for the employee reference sweep (matches the Excel export's paging). */
    private static final int EMPLOYEE_PAGE = 500;

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public AttendanceAnalyticsView build(Integer year, Integer month, UUID departmentId) {
        UUID tenantId = currentTenantId();
        YearMonth period = resolveMonth(year, month);
        YearMonth prev = period.minusMonths(1);
        YearMonth next = period.plusMonths(1);
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();

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
        List<AttendanceAnalyticsView.TrendPoint> trend = buildTrend(byDate, start, end);
        int trendMaxRate = trend.stream().mapToInt(AttendanceAnalyticsView.TrendPoint::barHeightPct).max().orElse(0);

        AttendanceAnalyticsView.Summary summary =
                buildSummary(perEmployee, byDate.size(), start, end);

        return new AttendanceAnalyticsView(
                period.getYear(), period.getMonthValue(), period.format(MONTH_LABEL),
                prev.getYear(), prev.getMonthValue(), next.getYear(), next.getMonthValue(),
                start.toString(), end.toString(),
                deptOptions, selectedDept,
                summary, employeeRows, departmentRows, trend, trendMaxRate);
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
        // (no working records, e.g. a month of only leave) sort last, then alphabetical by name.
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
            Map<UUID, Counts> perEmployee, int activeDays, LocalDate start, LocalDate end) {

        Counts total = new Counts();
        for (Counts c : perEmployee.values()) {
            total.add(c);
        }
        Integer rate = total.rate();
        int daysInRange = (int) (end.toEpochDay() - start.toEpochDay() + 1);
        return new AttendanceAnalyticsView.Summary(
                total.present, total.late, total.absent, total.halfDay, total.onLeave,
                total.total(), total.working(), rate, rateKind(rate),
                perEmployee.size(), activeDays, daysInRange);
    }

    // ── trend ────────────────────────────────────────────────────────────────────────

    private List<AttendanceAnalyticsView.TrendPoint> buildTrend(
            Map<LocalDate, Counts> byDate, LocalDate start, LocalDate end) {

        LocalDate today = LocalDate.now();
        List<AttendanceAnalyticsView.TrendPoint> points = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Counts c = byDate.getOrDefault(d, Counts.EMPTY);
            Integer rate = c.rate();
            int bar = rate == null ? 0 : Math.max(0, Math.min(100, rate));
            boolean weekend = switch (d.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> true;
                default -> false;
            };
            String tooltip = d.format(TOOLTIP_DATE) + " — "
                    + (rate == null ? "no records" : rate + "%")
                    + " (P" + c.present + " L" + c.late + " A" + c.absent + " H" + c.halfDay + ")";
            points.add(new AttendanceAnalyticsView.TrendPoint(
                    d.toString(), String.valueOf(d.getDayOfMonth()),
                    c.present, c.late, c.absent, c.halfDay, c.onLeave,
                    rate, bar, weekend, d.equals(today), tooltip));
        }
        return points;
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

    private YearMonth resolveMonth(Integer year, Integer month) {
        if (year == null || month == null) {
            return YearMonth.now();
        }
        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException e) {
            return YearMonth.now();
        }
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
