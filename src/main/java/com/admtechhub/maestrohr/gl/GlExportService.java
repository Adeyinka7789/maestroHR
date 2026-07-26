package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.gl.GlDtos.GlConfigView;
import com.admtechhub.maestrohr.gl.GlDtos.JournalView;
import com.admtechhub.maestrohr.gl.GlDtos.JournalView.JournalLine;
import com.admtechhub.maestrohr.gl.GlDtos.RunOption;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds an ERP-ready, always-balanced payroll journal for a finalized run and exports it as CSV
 * (generic or QuickBooks-shaped). Salary/wages expense is the earnings plug (net + PAYE + employee
 * pension + NHF + other withholdings) attributed per {@link CostCenter}; employer pension is a
 * separate expense; the statutory withholdings and net pay are consolidated credits. Debits always
 * equal credits by construction (see {@link #buildJournal}).
 */
@Service
@RequiredArgsConstructor
public class GlExportService {

    /** Runs whose figures are final enough to post to the ledger. */
    private static final List<PayrollStatus> FINALIZED = List.of(
            PayrollStatus.APPROVED, PayrollStatus.DISBURSING,
            PayrollStatus.DISBURSING_UNKNOWN, PayrollStatus.COMPLETED);

    public enum ExportFormat { GENERIC, QUICKBOOKS }

    private final GlAccountConfigRepository configRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;

    // ── Config ───────────────────────────────────────────────────────────────────

    /** The tenant's GL account config, or an in-memory default (not persisted until edited). */
    @Transactional(readOnly = true)
    public GlAccountConfig getConfigOrDefault() {
        return configRepository.findFirstBy()
                .orElseGet(() -> GlAccountConfig.builder().tenantId(currentTenantId()).build());
    }

    @Transactional(readOnly = true)
    public GlConfigView getConfigView() {
        return GlConfigView.from(getConfigOrDefault());
    }

    @Transactional
    public void updateConfig(GlConfigView view) {
        GlAccountConfig c = configRepository.findFirstBy()
                .orElseGet(() -> GlAccountConfig.builder().tenantId(currentTenantId()).build());
        c.setSalaryExpenseAccount(orDefault(view.salaryExpenseAccount(), c.getSalaryExpenseAccount()));
        c.setPensionExpenseAccount(orDefault(view.pensionExpenseAccount(), c.getPensionExpenseAccount()));
        c.setNetPayAccount(orDefault(view.netPayAccount(), c.getNetPayAccount()));
        c.setPayePayableAccount(orDefault(view.payePayableAccount(), c.getPayePayableAccount()));
        c.setPensionPayableAccount(orDefault(view.pensionPayableAccount(), c.getPensionPayableAccount()));
        c.setNhfPayableAccount(orDefault(view.nhfPayableAccount(), c.getNhfPayableAccount()));
        c.setOtherDeductionsAccount(orDefault(view.otherDeductionsAccount(), c.getOtherDeductionsAccount()));
        configRepository.save(c);
    }

    // ── Runs ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RunOption> listFinalizedRuns() {
        return payrollRunRepository.findByStatusInOrderByPeriodDesc(FINALIZED).stream()
                .map(r -> new RunOption(r.getId(), periodLabel(r.getPayrollMonth(), r.getPayrollYear()),
                        humanize(r.getStatus().name())))
                .toList();
    }

    // ── Journal ──────────────────────────────────────────────────────────────────

    /** Small mutable accumulator per cost center. */
    private static final class CcAgg {
        final String name;
        final String salaryAccount;
        long salaryExpense;
        long employerPension;
        CcAgg(String name, String salaryAccount) {
            this.name = name;
            this.salaryAccount = salaryAccount;
        }
    }

    @Transactional(readOnly = true)
    public JournalView buildJournal(UUID runId) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found."));
        GlAccountConfig cfg = getConfigOrDefault();
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunIdWithEntities(runId);

        Map<String, CcAgg> byCostCenter = new LinkedHashMap<>();
        long totalNet = 0, totalPaye = 0, totalPension = 0, totalNhf = 0, totalOther = 0;

        for (PayrollEntry e : entries) {
            long other = n(e.getOtherDeductions()) + n(e.getLoanDeduction()) + n(e.getAdjustmentDeduction())
                    + n(e.getPretaxDeduction()) + n(e.getAttendanceDeduction()) + n(e.getLateDeduction())
                    + n(e.getUnpaidLeaveDeduction());
            long salaryExpense = n(e.getNetSalary()) + n(e.getPayeTax()) + n(e.getPensionEmployee())
                    + n(e.getNhfDeduction()) + other;
            long employerPension = n(e.getPensionEmployer());

            Employee emp = e.getEmployee();
            CostCenter cc = emp != null ? emp.getCostCenter() : null;
            String key = cc != null ? cc.getId().toString() : "UNASSIGNED";
            String ccName = cc != null ? cc.getName() : "Unassigned";
            String ccAccount = cc != null && cc.getGlAccountCode() != null && !cc.getGlAccountCode().isBlank()
                    ? cc.getGlAccountCode() : cfg.getSalaryExpenseAccount();

            CcAgg agg = byCostCenter.computeIfAbsent(key, k -> new CcAgg(ccName, ccAccount));
            agg.salaryExpense += salaryExpense;
            agg.employerPension += employerPension;

            totalNet += n(e.getNetSalary());
            totalPaye += n(e.getPayeTax());
            totalPension += n(e.getPensionEmployee()) + n(e.getPensionEmployer());
            totalNhf += n(e.getNhfDeduction());
            totalOther += other;
        }

        String period = periodLabel(run.getPayrollMonth(), run.getPayrollYear());
        String debitMemoBase = "Payroll " + period;
        List<JournalLine> lines = new ArrayList<>();

        // Debits — expenses attributed per cost center.
        for (CcAgg agg : byCostCenter.values()) {
            if (agg.salaryExpense != 0) {
                lines.add(debit(agg.salaryAccount, "Salary & Wages Expense", agg.name,
                        agg.salaryExpense, debitMemoBase + " — " + agg.name));
            }
            if (agg.employerPension != 0) {
                lines.add(debit(cfg.getPensionExpenseAccount(), "Employer Pension Expense", agg.name,
                        agg.employerPension, debitMemoBase + " — " + agg.name));
            }
        }

        // Credits — consolidated liabilities.
        addCredit(lines, cfg.getNetPayAccount(), "Net Pay / Bank", totalNet, debitMemoBase);
        addCredit(lines, cfg.getPayePayableAccount(), "PAYE Payable (LIRS/OGIRS)", totalPaye, debitMemoBase);
        addCredit(lines, cfg.getPensionPayableAccount(), "Pension Payable (PFA)", totalPension, debitMemoBase);
        addCredit(lines, cfg.getNhfPayableAccount(), "NHF Payable", totalNhf, debitMemoBase);
        addCredit(lines, cfg.getOtherDeductionsAccount(), "Other Deductions Payable", totalOther, debitMemoBase);

        long totalDebit = lines.stream().mapToLong(JournalLine::debitKobo).sum();
        long totalCredit = lines.stream().mapToLong(JournalLine::creditKobo).sum();

        return new JournalView(runId, period, humanize(run.getStatus().name()), lines,
                formatNaira(totalDebit), formatNaira(totalCredit), totalDebit == totalCredit);
    }

    // ── CSV export ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID runId, ExportFormat format) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found."));
        JournalView journal = buildJournal(runId);
        StringBuilder sb = new StringBuilder();

        if (format == ExportFormat.QUICKBOOKS) {
            LocalDate date = YearMonth.of(run.getPayrollYear(), run.getPayrollMonth()).atEndOfMonth();
            String journalNo = String.format("PAY-%d%02d", run.getPayrollYear(), run.getPayrollMonth());
            sb.append("Date,Journal No,Account,Debits,Credits,Description,Name,Class\n");
            for (JournalLine l : journal.lines()) {
                sb.append(csv(date.toString())).append(',')
                        .append(csv(journalNo)).append(',')
                        .append(csv(l.accountCode())).append(',')
                        .append(amount(l.debitKobo())).append(',')
                        .append(amount(l.creditKobo())).append(',')
                        .append(csv(l.memo())).append(',')
                        .append(csv("")).append(',')
                        .append(csv(l.debitKobo() != 0 ? l.costCenter() : "")).append('\n');
            }
        } else {
            sb.append("Account Code,Account Name,Cost Center,Debit,Credit,Memo\n");
            for (JournalLine l : journal.lines()) {
                sb.append(csv(l.accountCode())).append(',')
                        .append(csv(l.accountName())).append(',')
                        .append(csv(l.debitKobo() != 0 ? l.costCenter() : "")).append(',')
                        .append(amount(l.debitKobo())).append(',')
                        .append(amount(l.creditKobo())).append(',')
                        .append(csv(l.memo())).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Suggested download filename for a run's export. */
    public String fileName(UUID runId, ExportFormat format) {
        JournalView j = buildJournal(runId);
        String suffix = format == ExportFormat.QUICKBOOKS ? "-quickbooks" : "";
        return "gl-journal-" + j.periodLabel().toLowerCase(Locale.ENGLISH).replace(' ', '-') + suffix + ".csv";
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private JournalLine debit(String account, String name, String costCenter, long kobo, String memo) {
        return new JournalLine(account, name, costCenter, kobo, 0L, formatNaira(kobo), "", memo);
    }

    private void addCredit(List<JournalLine> lines, String account, String name, long kobo, String memo) {
        if (kobo != 0) {
            lines.add(new JournalLine(account, name, "", 0L, kobo, "", formatNaira(kobo), memo));
        }
    }

    private long n(Long v) {
        return v != null ? v : 0L;
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String periodLabel(int month, int year) {
        return Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + year;
    }

    private String formatNaira(long kobo) {
        return String.format(Locale.ENGLISH, "₦%,d", kobo / 100);
    }

    /** CSV numeric field: naira with 2 decimals, or blank for zero. */
    private String amount(long kobo) {
        return kobo == 0 ? "" : String.format(Locale.ENGLISH, "%.2f", kobo / 100.0);
    }

    /** Minimal CSV escaping: quote when the value contains a comma, quote, or newline. */
    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String humanize(String raw) {
        String lower = raw.replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
