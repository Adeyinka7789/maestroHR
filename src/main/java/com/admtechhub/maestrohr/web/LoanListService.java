package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.loan.EmployeeLoan;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.loan.LoanStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Assembles the server-rendered {@link LoanListView} for the HR/Finance loan approval +
 * tracking page. One tenant-scoped loan query backs the table. Mirrors {@link LeaveListService}.
 */
@Service
@RequiredArgsConstructor
public class LoanListService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private final LoanService loanService;

    @Transactional(readOnly = true)
    public LoanListView build() {
        List<EmployeeLoan> loans = loanService.getLoansByTenant();
        List<LoanListView.Row> rows = loans.stream().map(this::toRow).toList();

        long pendingCount = loans.stream().filter(l -> l.getStatus() == LoanStatus.PENDING).count();
        long activeCount = loans.stream().filter(l -> l.getStatus() == LoanStatus.ACTIVE).count();
        long outstanding = loans.stream()
                .filter(l -> l.getStatus() == LoanStatus.ACTIVE)
                .mapToLong(l -> l.getRemainingBalance() != null ? l.getRemainingBalance() : 0L)
                .sum();

        return new LoanListView(rows, pendingCount, activeCount, formatNaira(outstanding));
    }

    private LoanListView.Row toRow(EmployeeLoan loan) {
        Employee e = loan.getEmployee();
        String statusName = loan.getStatus() != null ? loan.getStatus().name() : LoanStatus.PENDING.name();
        boolean pending = LoanStatus.PENDING.name().equals(statusName);
        boolean active = LoanStatus.ACTIVE.name().equals(statusName);
        boolean paused = LoanStatus.PAUSED.name().equals(statusName);

        return new LoanListView.Row(
                loan.getId(),
                e != null ? e.getFullName() : "—",
                e != null ? initials(e.getFirstName(), e.getLastName()) : "?",
                e != null && e.getEmployeeNumber() != null ? e.getEmployeeNumber() : "—",
                formatNaira(loan.getLoanAmount()),
                formatNaira(loan.getMonthlyInstallment()),
                formatNaira(loan.getRemainingBalance()),
                (loan.getMonthsPaid() != null ? loan.getMonthsPaid() : 0) + " / " + loan.getRepaymentMonths() + " months",
                loan.getStartDate() != null ? loan.getStartDate().format(DATE_FORMAT) : "—",
                loan.getDescription() != null ? loan.getDescription() : "",
                statusName,
                humanize(statusName),
                statusKind(statusName),
                pending,            // canApprove
                pending,            // canReject
                active,             // canPause
                paused,             // canResume
                active || paused);  // canCancel
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Loan status badge colour bucket. */
    private String statusKind(String status) {
        return switch (status) {
            case "ACTIVE" -> "success";
            case "PENDING", "PAUSED" -> "warn";
            case "CANCELLED", "REJECTED" -> "error";
            default -> "neutral"; // COMPLETED
        };
    }

    /** Kobo → "₦450,000" (whole naira, thousands-separated). */
    private String formatNaira(Long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", (kobo != null ? kobo : 0L) / 100);
    }

    private String initials(String first, String last) {
        char a = first != null && !first.isBlank() ? first.charAt(0) : '?';
        char b = last != null && !last.isBlank() ? last.charAt(0) : ' ';
        return (String.valueOf(a) + b).trim().toUpperCase(Locale.ENGLISH);
    }

    /** Turn "ACTIVE" into "Active". */
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
