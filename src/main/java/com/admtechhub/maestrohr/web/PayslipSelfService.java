package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles the {@link PayslipSelfView} for the employee self-service "My Payslips"
 * page. Read-only — the PDF download itself goes through the existing
 * {@code /reports/payslip} endpoint; this only lists which runs the employee has a
 * payslip for so the page renders fully populated with no client-side fetch.
 * Mirrors {@link AttendanceSelfService}.
 *
 * The query is tenant-scoped by the {@code @SQLRestriction} on {@link PayrollEntry},
 * and the controller passes the authenticated employee's own id — so a user only ever
 * sees their own payslips.
 */
@Service
@RequiredArgsConstructor
public class PayslipSelfService {

    /**
     * Runs the employee should be able to see a payslip for: approved and beyond.
     * DRAFT / PENDING_APPROVAL aren't final, and REJECTED never paid out — none of
     * those should surface a downloadable payslip.
     */
    private static final Set<PayrollStatus> VISIBLE_STATUSES =
            EnumSet.of(PayrollStatus.APPROVED, PayrollStatus.DISBURSING, PayrollStatus.COMPLETED);

    private final PayrollEntryRepository payrollEntryRepository;

    @Transactional(readOnly = true)
    public PayslipSelfView build(UUID employeeId, String employeeName) {
        List<PayslipSelfView.Row> rows = payrollEntryRepository
                .findByEmployeeIdOrderByPayrollRunCreatedAtDesc(employeeId).stream()
                .filter(entry -> VISIBLE_STATUSES.contains(entry.getPayrollRun().getStatus()))
                .map(this::toRow)
                .toList();

        return new PayslipSelfView(employeeName, employeeId, rows);
    }

    private PayslipSelfView.Row toRow(PayrollEntry entry) {
        PayrollRun run = entry.getPayrollRun();
        return new PayslipSelfView.Row(
                run.getId(),
                formatPeriod(run.getPayrollYear(), run.getPayrollMonth()),
                formatNaira(entry.getNetSalary()),
                statusLabel(run.getStatus()),
                statusKind(run.getStatus()));
    }

    /** "2026", 6 -> "June 2026". */
    private String formatPeriod(Integer year, Integer month) {
        String monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return monthName + " " + year;
    }

    /** Net pay is stored in kobo; render it as naira with thousands separators. */
    private String formatNaira(Long amountInKobo) {
        double naira = amountInKobo == null ? 0.0 : amountInKobo / 100.0;
        return "₦" + String.format("%,.2f", naira);
    }

    private String statusLabel(PayrollStatus status) {
        return switch (status) {
            case COMPLETED -> "Paid";
            case DISBURSING -> "Disbursing";
            default -> "Approved"; // APPROVED (the only remaining VISIBLE_STATUSES member)
        };
    }

    private String statusKind(PayrollStatus status) {
        return switch (status) {
            case COMPLETED -> "success";
            case DISBURSING -> "warn";
            default -> "neutral"; // APPROVED
        };
    }
}
