package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link LeaveListView} for the redesigned leave page
 * (the HR-admin / manager approval-queue + history table). A single
 * {@link LeaveRequestRepository#findFiltered} call backs the table and its
 * status/search filters, and a second grouped count query
 * ({@link LeaveRequestRepository#countByStatusForTenant}) feeds the filter chips, so
 * the page renders fully populated with no client-side fetches. Mirrors
 * {@link DepartmentListService} / {@link EmployeeListService}.
 */
@Service
@RequiredArgsConstructor
public class LeaveListService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    /** Statuses shown as filter chips, in display order. Excludes CANCELLED (no UI to create it). */
    private static final List<LeaveStatus> CHIP_STATUSES =
            List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED, LeaveStatus.REJECTED);

    private final LeaveRequestRepository leaveRequestRepository;

    @Transactional(readOnly = true)
    public LeaveListView buildList(String search, LeaveStatus status) {
        UUID tenantId = currentTenantId();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        List<LeaveRequest> results =
                leaveRequestRepository.findFiltered(tenantId, status, normalizedSearch);
        List<LeaveListView.Row> rows = results.stream().map(this::toRow).toList();

        return new LeaveListView(
                rows,
                rows.size(),
                normalizedSearch,
                status == null ? null : status.name(),
                buildChips(tenantId, status));
    }

    // ── chips ──────────────────────────────────────────────────────────────────

    /** Tenant-wide per-status counts (full data set, not the filtered view) → All + per-status chips. */
    private List<LeaveListView.StatusChip> buildChips(UUID tenantId, LeaveStatus active) {
        Map<LeaveStatus, Long> counts = new EnumMap<>(LeaveStatus.class);
        long total = 0;
        for (Object[] row : leaveRequestRepository.countByStatusForTenant(tenantId)) {
            LeaveStatus s = (LeaveStatus) row[0];
            long c = (Long) row[1];
            counts.put(s, c);
            total += c; // total includes CANCELLED, which has no chip of its own
        }

        List<LeaveListView.StatusChip> chips = new ArrayList<>(CHIP_STATUSES.size() + 1);
        chips.add(new LeaveListView.StatusChip("", "All", total, active == null));
        for (LeaveStatus s : CHIP_STATUSES) {
            chips.add(new LeaveListView.StatusChip(
                    s.name(), humanize(s.name()), counts.getOrDefault(s, 0L), s == active));
        }
        return chips;
    }

    // ── row mapping ──────────────────────────────────────────────────────────────

    private LeaveListView.Row toRow(LeaveRequest r) {
        String statusName = r.getStatus() != null ? r.getStatus().name() : LeaveStatus.PENDING.name();
        return new LeaveListView.Row(
                r.getId(),
                r.getEmployee().getFullName(),
                initials(r.getEmployee().getFirstName(), r.getEmployee().getLastName()),
                r.getLeaveType().getName(),
                formatDate(r.getStartDate()),
                formatDate(r.getEndDate()),
                r.getDaysRequested() != null ? r.getDaysRequested() : 0,
                r.getReason() != null ? r.getReason() : "",
                statusName,
                humanize(statusName),
                statusKind(statusName),
                r.getCreatedAt() == null ? "—" : r.getCreatedAt().format(DATE_FORMAT));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the dashboard/employees success/warn/error/neutral scheme. */
    private String statusKind(String status) {
        return switch (status) {
            case "APPROVED" -> "success";
            case "PENDING" -> "warn";
            case "REJECTED" -> "error";
            default -> "neutral"; // CANCELLED
        };
    }

    private String formatDate(LocalDate date) {
        return date == null ? "—" : date.format(DATE_FORMAT);
    }

    private String initials(String first, String last) {
        char a = first != null && !first.isBlank() ? first.charAt(0) : '?';
        char b = last != null && !last.isBlank() ? last.charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
    }

    /** Turn "PENDING" into "Pending". */
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
