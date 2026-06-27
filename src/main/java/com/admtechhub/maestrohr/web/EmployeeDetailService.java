package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.loan.EmployeeLoan;
import com.admtechhub.maestrohr.loan.LoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Assembles the server-rendered {@link EmployeeDetailView} for the employee detail
 * page. Backed by {@link EmployeeService#getEmployeeByIdWithDetails} (tenant-scoped
 * via the {@code @SQLRestriction} on the entities, and which touches the lazy
 * department / pay grade), so the page renders fully populated with no client-side
 * fetch. Mirrors {@link DepartmentDetailService}.
 */
@Service
@RequiredArgsConstructor
public class EmployeeDetailService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private final EmployeeService employeeService;
    private final LoanService loanService;

    /**
     * Builds the detail view for {@code employeeId}, or {@code null} if no such
     * employee exists for the current tenant (the {@code @SQLRestriction} hides other
     * tenants' rows, so a foreign id reads as not-found — surfaced here as the
     * {@link IllegalArgumentException} the service throws on a missing record).
     */
    @Transactional(readOnly = true)
    public EmployeeDetailView buildDetail(UUID employeeId) {
        Employee e;
        try {
            e = employeeService.getEmployeeByIdWithDetails(employeeId);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        String statusName = e.getStatus() != null ? e.getStatus().name() : EmployeeStatus.ACTIVE.name();
        PayGrade pg = e.getPayGrade();
        boolean hasPayGrade = pg != null;

        // Action gating: terminate is HR/super-admin and only while still active; hard delete
        // is super-admin only and only when nothing depends on the record (checked against the
        // payroll/leave/attendance/HOD tables). Both are also re-enforced server-side on the action.
        boolean terminated = EmployeeStatus.TERMINATED.name().equals(statusName);
        boolean canTerminate = hasAnyRole("ROLE_HR_ADMIN", "ROLE_SUPER_ADMIN") && !terminated;
        boolean canHardDelete = hasAnyRole("ROLE_SUPER_ADMIN") && employeeService.canHardDelete(e.getId());

        return new EmployeeDetailView(
                e.getId(),
                e.getFullName(),
                orDash(e.getJobTitle()),
                orDash(e.getEmployeeNumber()),
                statusName,
                humanize(statusName),
                statusKind(statusName),

                // Personal information
                orDash(e.getEmail()),
                orDash(e.getPhone()),
                formatDate(e.getDateOfBirth()),
                e.getGender() != null ? humanize(e.getGender().name()) : "—",
                e.getMaritalStatus() != null ? humanize(e.getMaritalStatus().name()) : "—",
                orDash(e.getAddress()),

                // Employment details
                e.getDepartment() != null ? e.getDepartment().getName() : "Not assigned",
                e.getEmploymentType() != null ? humanize(e.getEmploymentType().name()) : "—",
                formatDate(e.getEmploymentStartDate()),
                e.getProbationEndDate() != null ? formatDate(e.getProbationEndDate()) : "Completed",

                // Bank details
                orDash(e.getBankName()),
                orDash(e.getBankAccountNumber()),
                orDash(e.getBankAccountName()),
                e.getPaystackRecipientCode() != null && !e.getPaystackRecipientCode().isBlank()
                        ? e.getPaystackRecipientCode() : "Not linked",

                // Salary (current pay grade)
                hasPayGrade,
                hasPayGrade ? pg.getName() : "No grade assigned",
                hasPayGrade ? formatNaira(pg.getBasicSalary()) : null,
                hasPayGrade ? formatNaira(pg.getHousingAllowance()) : null,
                hasPayGrade ? formatNaira(pg.getTransportAllowance()) : null,
                hasPayGrade ? formatNaira(pg.getGrossSalary()) : null,

                // Summary
                formatDate(e.getEmploymentStartDate()),
                formatDate(e.getCreatedAt()),

                // Action permissions
                canTerminate,
                canHardDelete,

                // Loans
                buildLoanSummaries(e.getId()));
    }

    /** Read-only loan summaries for the employee, newest first (empty when none). */
    private List<EmployeeDetailView.LoanSummary> buildLoanSummaries(UUID employeeId) {
        return loanService.getLoansByEmployee(employeeId).stream()
                .map(this::toLoanSummary)
                .toList();
    }

    private EmployeeDetailView.LoanSummary toLoanSummary(EmployeeLoan loan) {
        String statusName = loan.getStatus() != null ? loan.getStatus().name() : "ACTIVE";
        return new EmployeeDetailView.LoanSummary(
                formatNaira(loan.getLoanAmount()),
                formatNaira(loan.getMonthlyInstallment()),
                formatNaira(loan.getRemainingBalance()),
                (loan.getMonthsPaid() != null ? loan.getMonthsPaid() : 0) + " / " + loan.getRepaymentMonths() + " months",
                formatDate(loan.getStartDate()),
                loan.getDescription() != null ? loan.getDescription() : "",
                humanize(statusName),
                loanStatusKind(statusName));
    }

    /** Loan status badge colour bucket. */
    private String loanStatusKind(String status) {
        return switch (status) {
            case "ACTIVE" -> "success";
            case "PENDING", "PAUSED" -> "warn";
            case "CANCELLED", "REJECTED" -> "error";
            default -> "neutral"; // COMPLETED
        };
    }

    /** True when the authenticated viewer holds any of the given ROLE_* authorities. */
    private boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            for (String role : roles) {
                if (role.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Status badge colour bucket, matching the employees list / department detail scheme. */
    private String statusKind(String status) {
        return switch (status) {
            case "ACTIVE" -> "success";
            case "ONBOARDING", "ON_LEAVE" -> "warn";
            case "SUSPENDED" -> "error";
            default -> "neutral"; // TERMINATED
        };
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** Pay-grade salaries are stored in kobo; render whole naira with thousands separators. */
    private String formatNaira(Long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", (kobo != null ? kobo : 0L) / 100);
    }

    private String formatDate(OffsetDateTime value) {
        return value == null ? "—" : value.format(DATE_FORMAT);
    }

    private String formatDate(LocalDate value) {
        return value == null ? "—" : value.format(DATE_FORMAT);
    }

    /** Turn "ON_LEAVE" / "FULL_TIME" into "On Leave" / "Full Time". */
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
}
