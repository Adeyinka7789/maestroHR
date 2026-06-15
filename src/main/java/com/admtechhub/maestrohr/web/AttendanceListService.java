package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link AttendanceListView} for the redesigned
 * attendance page (the HR-admin / manager daily roster). A single
 * {@link AttendanceRepository#findFilteredByDate} call backs the table and its
 * status/search filters, and a second grouped count query
 * ({@link AttendanceRepository#countByStatusForDate}) feeds the summary stat cards
 * and filter chips, so the page renders fully populated with no client-side fetches.
 * Mirrors {@link LeaveListService} / {@link DepartmentListService}.
 *
 * SCOPE (Step A): read-only single-day list + date picker + status filter + search.
 */
@Service
@RequiredArgsConstructor
public class AttendanceListService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** Statuses shown as filter chips, in display order. Excludes ON_LEAVE (surfaced only under "All"). */
    private static final List<AttendanceStatus> CHIP_STATUSES = List.of(
            AttendanceStatus.PRESENT,
            AttendanceStatus.LATE,
            AttendanceStatus.ABSENT,
            AttendanceStatus.HALF_DAY);

    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public AttendanceListView buildList(LocalDate date, String search, AttendanceStatus status) {
        UUID tenantId = currentTenantId();
        LocalDate day = date != null ? date : LocalDate.now();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        List<AttendanceRecord> results =
                attendanceRepository.findFilteredByDate(tenantId, day, status, normalizedSearch);
        List<AttendanceListView.Row> rows = results.stream().map(this::toRow).toList();

        return new AttendanceListView(
                rows,
                rows.size(),
                day.format(ISO_DATE),
                day.format(DATE_FORMAT),
                normalizedSearch,
                status == null ? null : status.name(),
                buildChips(tenantId, day, status));
    }

    // ── chips ──────────────────────────────────────────────────────────────────

    /** Per-status counts for the selected day → All + per-status chips (full day's roster). */
    private List<AttendanceListView.StatusChip> buildChips(UUID tenantId, LocalDate day, AttendanceStatus active) {
        Map<AttendanceStatus, Long> counts = new EnumMap<>(AttendanceStatus.class);
        long total = 0;
        for (Object[] row : attendanceRepository.countByStatusForDate(tenantId, day)) {
            AttendanceStatus s = (AttendanceStatus) row[0];
            long c = (Long) row[1];
            counts.put(s, c);
            total += c; // total includes ON_LEAVE, which has no chip of its own
        }

        List<AttendanceListView.StatusChip> chips = new ArrayList<>(CHIP_STATUSES.size() + 1);
        chips.add(new AttendanceListView.StatusChip("", "All", total, active == null));
        for (AttendanceStatus s : CHIP_STATUSES) {
            chips.add(new AttendanceListView.StatusChip(
                    s.name(), humanize(s.name()), counts.getOrDefault(s, 0L), s == active));
        }
        return chips;
    }

    // ── row mapping ──────────────────────────────────────────────────────────────

    private AttendanceListView.Row toRow(AttendanceRecord r) {
        String statusName = r.getStatus() != null ? r.getStatus().name() : AttendanceStatus.PRESENT.name();
        return new AttendanceListView.Row(
                r.getId(),
                r.getEmployee().getFullName(),
                initials(r.getEmployee().getFirstName(), r.getEmployee().getLastName()),
                formatTime(r.getClockInTime()),
                formatTime(r.getClockOutTime()),
                formatHours(r.getHoursWorked()),
                statusName,
                humanize(statusName),
                statusKind(statusName));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the leave/dashboard success/warn/error/neutral scheme. */
    private String statusKind(String status) {
        return switch (status) {
            case "PRESENT" -> "success";
            case "LATE", "HALF_DAY" -> "warn";
            case "ABSENT" -> "error";
            default -> "neutral"; // ON_LEAVE
        };
    }

    private String formatTime(LocalTime time) {
        return time == null ? "—" : time.format(TIME_FORMAT);
    }

    private String formatHours(BigDecimal hours) {
        return hours == null ? "—" : hours.stripTrailingZeros().toPlainString();
    }

    private String initials(String first, String last) {
        char a = first != null && !first.isBlank() ? first.charAt(0) : '?';
        char b = last != null && !last.isBlank() ? last.charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
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
