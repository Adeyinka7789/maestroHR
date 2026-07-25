package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.admtechhub.maestrohr.web.PayrollListService.formatNaira;
import static com.admtechhub.maestrohr.web.PayrollListService.humanize;
import static com.admtechhub.maestrohr.web.PayrollListService.periodLabel;
import static com.admtechhub.maestrohr.web.PayrollListService.statusKind;

/**
 * Assembles the server-rendered {@link PayrollDetailView} for a single payroll run
 * (statutory summary + per-employee breakdown). The run is looked up tenant-scoped via
 * the {@code @SQLRestriction} on {@link PayrollRun}, so a run from another tenant is
 * simply "not found". Reuses the formatting/status helpers from
 * {@link PayrollListService} to keep the two payroll views consistent.
 *
 * SCOPE (Step B): read-only. The {@code canSubmit / canApprove / canReject / editable}
 * flags are derived from the run's status so the template can render the Step C/D action
 * buttons correctly; the write handlers are deferred.
 */
@Service
@RequiredArgsConstructor
public class PayrollDetailService {

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm", Locale.ENGLISH);

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;

    @Transactional(readOnly = true)
    public PayrollDetailView build(UUID payrollRunId) {
        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        List<PayrollDetailView.EntryRow> rows = entries.stream().map(this::toEntryRow).toList();

        String statusName = run.getStatus() != null ? run.getStatus().name() : PayrollStatus.DRAFT.name();

        return new PayrollDetailView(
                run.getId(),
                run.getPeriod(),
                periodLabel(run),
                statusName,
                humanize(statusName),
                statusKind(statusName),

                formatNaira(run.getTotalGross()),
                formatNaira(run.getTotalNet()),
                formatNaira(run.getTotalPaye()),
                formatNaira(run.getTotalPensionEmployee()),
                formatNaira(run.getTotalPensionEmployer()),
                formatNaira(run.getTotalNhf()),
                rows.size(),

                run.getInitiatedBy() != null ? run.getInitiatedBy().getEmail() : "—",
                run.getApprovedBy() != null ? run.getApprovedBy().getEmail() : null,
                run.getApprovedAt() != null ? run.getApprovedAt().format(DATE_TIME_FORMAT) : "—",
                run.getRejectionReason(),
                run.getReversedBy() != null ? run.getReversedBy().getEmail() : null,
                run.getReversedAt() != null ? run.getReversedAt().format(DATE_TIME_FORMAT) : null,
                run.getReversalReason(),

                run.isEditable(),
                run.canSubmit(),
                run.canApprove(),
                run.canReject(),

                // Effective visibility: status flag AND the viewer's role matches the gate
                // on the corresponding /htmx/payroll/{id}/… write endpoint. Approve is
                // FINANCE_OFFICER/SUPER_ADMIN (segregation of duties — the initiator cannot
                // approve); submit is HR_ADMIN/SUPER_ADMIN; compute and reject follow the
                // initiate gate (HR_ADMIN/FINANCE_OFFICER/SUPER_ADMIN), matching their REST
                // endpoints — reject is not approval, so the initiator role may send a run back.
                run.isEditable() && hasAnyRole("HR_ADMIN", "SYSTEM_ADMIN", "FINANCE_OFFICER", "SUPER_ADMIN"),
                run.canSubmit() && hasAnyRole("HR_ADMIN", "SYSTEM_ADMIN", "SUPER_ADMIN"),
                run.canApprove() && hasAnyRole("FINANCE_OFFICER", "SUPER_ADMIN"),
                run.canReject() && hasAnyRole("HR_ADMIN", "SYSTEM_ADMIN", "FINANCE_OFFICER", "SUPER_ADMIN"),

                // Step D: export once figures are final (APPROVED onward); mark-paid completes
                // the lifecycle and, like approve, is finance-only (segregation of duties).
                exportable(run.getStatus()) && hasAnyRole("HR_ADMIN", "SYSTEM_ADMIN", "FINANCE_OFFICER", "SUPER_ADMIN"),
                run.canComplete() && hasAnyRole("FINANCE_OFFICER", "SUPER_ADMIN"),

                // Reversal: run must be APPROVED/DISBURSING/COMPLETED; SYSTEM_ADMIN or FINANCE_OFFICER only.
                run.canReverse() && hasAnyRole("SYSTEM_ADMIN", "FINANCE_OFFICER", "SUPER_ADMIN"),

                rows);
    }

    /** The payment file may be exported once a run's figures are final: APPROVED onward (including REVERSED for audit). */
    private static boolean exportable(PayrollStatus status) {
        return status == PayrollStatus.APPROVED
                || status == PayrollStatus.DISBURSING
                || status == PayrollStatus.COMPLETED
                || status == PayrollStatus.REVERSED;
    }

    /** True when the authenticated viewer holds any of the given roles (matched as ROLE_… authorities). */
    private static boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        Set<String> granted = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        for (String role : roles) {
            if (granted.contains("ROLE_" + role)) {
                return true;
            }
        }
        return false;
    }

    // ── row mapping ──────────────────────────────────────────────────────────────────

    private PayrollDetailView.EntryRow toEntryRow(PayrollEntry e) {
        Employee emp = e.getEmployee();
        String name = emp != null && emp.getFullName() != null ? emp.getFullName() : "Deleted employee";
        String number = emp != null && emp.getEmployeeNumber() != null ? emp.getEmployeeNumber() : "—";
        String initials = emp != null
                ? initials(emp.getFirstName(), emp.getLastName())
                : "?";
        return new PayrollDetailView.EntryRow(
                e.getId(),
                name,
                number,
                initials,
                formatNaira(e.getGrossSalary()),
                formatNaira(e.getPayeTax()),
                formatNaira(e.getPensionEmployee()),
                formatNaira(e.getNhfDeduction()),
                formatNaira(e.getUnpaidLeaveDeduction()),
                formatNaira(e.getAttendanceDeduction()),
                formatNaira(nz(e.getTaxableEarnings()) + nz(e.getNonTaxableEarnings())),
                formatNaira(nz(e.getPretaxDeduction()) + nz(e.getAdjustmentDeduction())),
                e.getLateDaysInPeriod() != null ? e.getLateDaysInPeriod() : 0,
                formatNaira(e.getNetSalary()));
    }

    /** Null-safe kobo accessor for the optional adjustment columns. */
    private static long nz(Long value) {
        return value != null ? value : 0L;
    }

    private String initials(String first, String last) {
        char a = first != null && !first.isBlank() ? first.charAt(0) : '?';
        char b = last != null && !last.isBlank() ? last.charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
    }

    private UUID currentTenantId() {
        return UUID.fromString(TenantContext.getCurrentTenant());
    }
}
