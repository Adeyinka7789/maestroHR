package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.PayGradeListRow;
import com.admtechhub.maestrohr.employee.PayGradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link PayGradeListView} for the redesigned
 * pay-grades page. A single {@link PayGradeRepository#findFilteredWithEmployeeCount}
 * call backs the card grid and its optional name search, so the page renders fully
 * populated with no client-side fetches. Mirrors {@link DepartmentListService}.
 *
 * Salary components are stored in kobo and rendered here as whole-naira strings.
 * The summary stats (highest / average gross) and each card's relative-pay bar are
 * computed over the rows returned for the current view: the unfiltered set on the
 * initial load, or the matching subset when a search swaps the grid fragment.
 */
@Service
@RequiredArgsConstructor
public class PayGradeListService {

    private final PayGradeRepository payGradeRepository;

    @Transactional(readOnly = true)
    public PayGradeListView buildList(String search) {
        UUID tenantId = currentTenantId();
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        List<PayGradeListRow> results =
                payGradeRepository.findFilteredWithEmployeeCount(tenantId, normalizedSearch);

        long highestGross = results.stream().mapToLong(this::gross).max().orElse(0L);
        long avgGross = results.isEmpty()
                ? 0L
                : Math.round(results.stream().mapToLong(this::gross).average().orElse(0));

        List<PayGradeListView.Row> rows =
                results.stream().map(r -> toRow(r, highestGross)).toList();

        return new PayGradeListView(
                rows,
                results.size(),
                formatNaira(highestGross),
                formatNaira(avgGross),
                normalizedSearch);
    }

    private PayGradeListView.Row toRow(PayGradeListRow r, long highestGross) {
        long g = gross(r);
        int relativePct = highestGross > 0 ? (int) Math.round(g * 100.0 / highestGross) : 0;

        return new PayGradeListView.Row(
                r.getId(),
                r.getName(),
                r.getIsActive() == null || r.getIsActive(),
                r.getEmployeeCount() != null ? r.getEmployeeCount() : 0L,
                nz(r.getBasicSalary()),
                nz(r.getHousingAllowance()),
                nz(r.getTransportAllowance()),
                nz(r.getOtherAllowances()),
                formatNaira(nz(r.getBasicSalary())),
                formatNaira(nz(r.getHousingAllowance())),
                formatNaira(nz(r.getTransportAllowance())),
                formatNaira(nz(r.getOtherAllowances())),
                formatNaira(g),
                relativePct,
                r.getLoanPolicyId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Gross salary in kobo: basic + all allowances (mirrors PayGrade#getGrossSalary). */
    private long gross(PayGradeListRow r) {
        return nz(r.getBasicSalary())
                + nz(r.getHousingAllowance())
                + nz(r.getTransportAllowance())
                + nz(r.getOtherAllowances());
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** Salary components are stored in kobo; render whole naira with thousands separators. */
    private String formatNaira(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
