package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link AttendanceCalendarView} for the attendance
 * Monthly Calendar fragment (Step B) — the HR-admin / manager per-employee month grid.
 * A single month-and-employee-scoped query
 * ({@link AttendanceRepository#findForEmployeeMonth}) backs the grid and the present-rate
 * summary, and a tenant-scoped employee lookup feeds the picker, so the page renders
 * fully populated with no client-side fetches. Companion to the Step-A
 * {@link AttendanceListService}; kept separate because the month grid is structurally
 * unlike the daily roster and the Step-A list classes are documented as roster-scoped.
 *
 * Present-rate semantics (per product decision): denominator = days WITH a record
 * (so a sparse / mid-month view is not reported as misleadingly low), numerator =
 * PRESENT records only, rate null when there are no records. See {@link AttendanceCalendarView}.
 *
 * SCOPE (Step B): read-only. No write actions.
 */
@Service
@RequiredArgsConstructor
public class AttendanceCalendarService {

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);

    /** Upper bound on employees loaded into the picker (no pagination on the calendar page). */
    private static final int MAX_EMPLOYEES = 2000;

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public AttendanceCalendarView build(UUID employeeId, Integer year, Integer month) {
        UUID tenantId = currentTenantId();
        YearMonth period = resolveMonth(year, month);
        YearMonth prev = period.minusMonths(1);
        YearMonth next = period.plusMonths(1);

        List<AttendanceCalendarView.EmployeeOption> employees = loadEmployees(tenantId);

        // Validate the requested employee belongs to this tenant; an unknown / cross-tenant
        // id falls back to the "select an employee" prompt rather than rendering an empty grid.
        Employee selected = employeeId == null ? null
                : employeeRepository.findByIdAndTenantId(employeeId, tenantId).orElse(null);

        if (selected == null) {
            return new AttendanceCalendarView(
                    employees, null, null, false,
                    period.getYear(), period.getMonthValue(), period.format(MONTH_LABEL),
                    prev.getYear(), prev.getMonthValue(), next.getYear(), next.getMonthValue(),
                    List.of(), 0L, 0L, null);
        }

        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        List<AttendanceRecord> records =
                attendanceRepository.findForEmployeeMonth(tenantId, selected.getId(), start, end);

        // One record per day is expected; the map de-duplicates defensively so recordedDays
        // (the rate denominator) counts distinct dated days, not raw rows.
        Map<LocalDate, AttendanceStatus> byDate = new HashMap<>();
        for (AttendanceRecord r : records) {
            byDate.put(r.getAttendanceDate(), r.getStatus());
        }

        long presentDays = byDate.values().stream().filter(s -> s == AttendanceStatus.PRESENT).count();
        long recordedDays = byDate.size();
        Integer presentRate = recordedDays == 0 ? null
                : (int) Math.round(presentDays * 100.0 / recordedDays);

        return new AttendanceCalendarView(
                employees, selected.getId(), selected.getFullName(), true,
                period.getYear(), period.getMonthValue(), period.format(MONTH_LABEL),
                prev.getYear(), prev.getMonthValue(), next.getYear(), next.getMonthValue(),
                buildCells(period, byDate), presentDays, recordedDays, presentRate);
    }

    // ── month grid ─────────────────────────────────────────────────────────────────

    private List<AttendanceCalendarView.DayCell> buildCells(YearMonth period,
                                                            Map<LocalDate, AttendanceStatus> byDate) {
        List<AttendanceCalendarView.DayCell> cells = new ArrayList<>();

        // Leading blanks so day 1 sits under its weekday in a Sunday-start grid.
        // DayOfWeek is MON=1..SUN=7; % 7 maps SUN→0, MON→1, … SAT→6 = the blank count.
        int lead = period.atDay(1).getDayOfWeek().getValue() % 7;
        for (int i = 0; i < lead; i++) {
            cells.add(new AttendanceCalendarView.DayCell(null, true, null, null, null, "", false, false));
        }

        LocalDate today = LocalDate.now();
        int length = period.lengthOfMonth();
        for (int d = 1; d <= length; d++) {
            LocalDate date = period.atDay(d);
            boolean weekend = switch (date.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> true;
                default -> false;
            };
            AttendanceStatus status = byDate.get(date);
            if (status == null) {
                cells.add(new AttendanceCalendarView.DayCell(
                        d, false, null, null, null, "", date.equals(today), weekend));
            } else {
                cells.add(new AttendanceCalendarView.DayCell(
                        d, false, status.name(), humanize(status.name()),
                        statusKind(status), symbol(status), date.equals(today), weekend));
            }
        }
        return cells;
    }

    // ── employee picker ────────────────────────────────────────────────────────────

    private List<AttendanceCalendarView.EmployeeOption> loadEmployees(UUID tenantId) {
        return employeeRepository
                .findAllByTenantId(tenantId, PageRequest.of(0, MAX_EMPLOYEES, Sort.by("firstName", "lastName")))
                .map(e -> new AttendanceCalendarView.EmployeeOption(e.getId(), optionLabel(e)))
                .getContent();
    }

    private String optionLabel(Employee e) {
        String number = e.getEmployeeNumber();
        return (number == null || number.isBlank())
                ? e.getFullName()
                : e.getFullName() + " (" + number + ")";
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Resolve the requested month, falling back to the current month for absent/invalid input. */
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

    /** Single-glyph day marker. ON_LEAVE uses "L" (not blank) to disambiguate from "no record". */
    private String symbol(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> "✓";
            case ABSENT -> "✗";
            case LATE -> "⏰";
            case HALF_DAY -> "½";
            case ON_LEAVE -> "L";
        };
    }

    /** Cell colour bucket, matching the Step-A roster success/warn/error/neutral scheme. */
    private String statusKind(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> "success";
            case LATE, HALF_DAY -> "warn";
            case ABSENT -> "error";
            case ON_LEAVE -> "neutral";
        };
    }

    /** Turn "HALF_DAY" into "Half Day". */
    private String humanize(String raw) {
        String spaced = raw.replace('_', ' ').trim().toLowerCase(Locale.ENGLISH);
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
