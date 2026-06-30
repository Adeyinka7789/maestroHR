package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link PayrollListView} for the redesigned payroll-runs
 * page (the HR-admin / finance run history + approval queue). Mirrors
 * {@link LeaveListService} / {@link AttendanceListService}.
 *
 * Unlike the leave/attendance lists, the status + search filtering happens in-service
 * over the tenant's full run list rather than in a dedicated {@code findFiltered} query:
 * a tenant has at most a handful of runs per year (one per month), so the cardinality is
 * trivial and in-service filtering lets the search match the <em>formatted</em> period
 * ("2026-06") and initiator email without awkward SQL date formatting. The filter-chip
 * counts still come from a tenant-wide grouped query
 * ({@link PayrollRunRepository#countByStatusForTenant}) so they reflect the full data
 * set, and the per-run employee counts come from a single grouped query
 * ({@link PayrollEntryRepository#countEntriesByRunForTenant}) so we never lazy-load every
 * run's entry collection.
 *
 * SCOPE (Step A): read-only list + status filter + search. Writes are deferred.
 */
@Service
@RequiredArgsConstructor
public class PayrollListService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    /** Statuses shown as filter chips, in lifecycle order. */
    private static final List<PayrollStatus> CHIP_STATUSES = List.of(
            PayrollStatus.DRAFT,
            PayrollStatus.PENDING_APPROVAL,
            PayrollStatus.APPROVED,
            PayrollStatus.DISBURSING,
            PayrollStatus.COMPLETED,
            PayrollStatus.REJECTED,
            PayrollStatus.REVERSED);

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;

    @Transactional(readOnly = true)
    public PayrollListView buildList(String search, PayrollStatus status) {
        UUID tenantId = currentTenantId();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        Map<UUID, Long> entryCounts = entryCountsByRun(tenantId);

        List<PayrollListView.Row> rows = payrollRunRepository
                .findAllByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> matchesSearch(r, normalizedSearch))
                .map(r -> toRow(r, entryCounts.getOrDefault(r.getId(), 0L)))
                .toList();

        return new PayrollListView(
                rows,
                rows.size(),
                normalizedSearch,
                status == null ? null : status.name(),
                buildChips(tenantId, status));
    }

    // ── filtering ──────────────────────────────────────────────────────────────────

    /** Case-insensitive match over the formatted period and the initiator email. */
    private boolean matchesSearch(PayrollRun run, String search) {
        if (search == null) {
            return true;
        }
        String needle = search.toLowerCase(Locale.ENGLISH);
        String period = (run.getPeriod() + " " + periodLabel(run)).toLowerCase(Locale.ENGLISH);
        if (period.contains(needle)) {
            return true;
        }
        String email = run.getInitiatedBy() != null ? run.getInitiatedBy().getEmail() : null;
        return email != null && email.toLowerCase(Locale.ENGLISH).contains(needle);
    }

    // ── chips ──────────────────────────────────────────────────────────────────────

    /** Tenant-wide per-status counts (full data set, not the filtered view) → All + per-status chips. */
    private List<PayrollListView.StatusChip> buildChips(UUID tenantId, PayrollStatus active) {
        Map<PayrollStatus, Long> counts = new EnumMap<>(PayrollStatus.class);
        long total = 0;
        for (Object[] row : payrollRunRepository.countByStatusForTenant(tenantId)) {
            PayrollStatus s = (PayrollStatus) row[0];
            long c = (Long) row[1];
            counts.put(s, c);
            total += c;
        }

        List<PayrollListView.StatusChip> chips = new ArrayList<>(CHIP_STATUSES.size() + 1);
        chips.add(new PayrollListView.StatusChip("", "All", total, active == null));
        for (PayrollStatus s : CHIP_STATUSES) {
            chips.add(new PayrollListView.StatusChip(
                    s.name(), humanize(s.name()), counts.getOrDefault(s, 0L), s == active));
        }
        return chips;
    }

    // ── per-run entry counts ─────────────────────────────────────────────────────────

    private Map<UUID, Long> entryCountsByRun(UUID tenantId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : payrollEntryRepository.countEntriesByRunForTenant(tenantId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    // ── row mapping ──────────────────────────────────────────────────────────────────

    private PayrollListView.Row toRow(PayrollRun run, long entryCount) {
        String statusName = run.getStatus() != null ? run.getStatus().name() : PayrollStatus.DRAFT.name();
        return new PayrollListView.Row(
                run.getId(),
                run.getPeriod(),
                periodLabel(run),
                (int) entryCount,
                formatNaira(run.getTotalNet()),
                run.getInitiatedBy() != null ? run.getInitiatedBy().getEmail() : "—",
                statusName,
                humanize(statusName),
                statusKind(statusName),
                run.getCreatedAt() == null ? "—" : run.getCreatedAt().format(DATE_FORMAT));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the leave/attendance/dashboard scheme. */
    static String statusKind(String status) {
        return switch (status) {
            case "APPROVED", "COMPLETED" -> "success";
            case "PENDING_APPROVAL", "DISBURSING" -> "warn";
            case "REJECTED", "REVERSED" -> "error";
            default -> "neutral"; // DRAFT
        };
    }

    /** "June 2026" from a run's month/year. */
    static String periodLabel(PayrollRun run) {
        if (run.getPayrollMonth() == null || run.getPayrollYear() == null) {
            return run.getPeriod();
        }
        return Month.of(run.getPayrollMonth()).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + run.getPayrollYear();
    }

    /** Kobo Long → "₦1,234,567.00". */
    static String formatNaira(Long kobo) {
        long value = kobo != null ? kobo : 0L;
        return String.format(Locale.ENGLISH, "₦%,.2f", value / 100.0);
    }

    /** Turn "PENDING_APPROVAL" into "Pending Approval". */
    static String humanize(String raw) {
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
