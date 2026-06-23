package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.loan.EmployeeLoan;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.loan.LoanStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Assembles the {@link LoanSelfView} for the employee self-service "My Loans" page. Read-only
 * list of the authenticated employee's own loans; the controller always passes the session
 * employee's id, so a user only ever sees their own. Mirrors {@link PayslipSelfService}.
 */
@Service
@RequiredArgsConstructor
public class LoanSelfService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    private final LoanService loanService;

    @Transactional(readOnly = true)
    public LoanSelfView build(UUID employeeId, String employeeName) {
        List<LoanSelfView.Row> rows = loanService.getLoansByEmployee(employeeId).stream()
                .map(this::toRow)
                .toList();
        return new LoanSelfView(employeeName, employeeId, rows);
    }

    private LoanSelfView.Row toRow(EmployeeLoan loan) {
        String statusName = loan.getStatus() != null ? loan.getStatus().name() : LoanStatus.PENDING.name();
        return new LoanSelfView.Row(
                formatNaira(loan.getLoanAmount()),
                formatNaira(loan.getMonthlyInstallment()),
                formatNaira(loan.getRemainingBalance()),
                (loan.getMonthsPaid() != null ? loan.getMonthsPaid() : 0) + " / " + loan.getRepaymentMonths() + " months",
                loan.getStartDate() != null ? loan.getStartDate().format(DATE_FORMAT) : "—",
                loan.getDescription() != null ? loan.getDescription() : "",
                statusName,
                humanize(statusName),
                statusKind(statusName),
                loan.getRejectionReason() != null ? loan.getRejectionReason() : "");
    }

    private String statusKind(String status) {
        return switch (status) {
            case "ACTIVE" -> "success";
            case "PENDING", "PAUSED" -> "warn";
            case "CANCELLED", "REJECTED" -> "error";
            default -> "neutral"; // COMPLETED
        };
    }

    private String formatNaira(Long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", (kobo != null ? kobo : 0L) / 100);
    }

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
