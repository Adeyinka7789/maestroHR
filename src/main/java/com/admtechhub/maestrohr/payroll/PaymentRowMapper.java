package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.employee.Employee;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps a {@link PayrollEntry} to a {@link PaymentRow} for the bank payment-file export.
 *
 * Bank-code resolution is deliberately NON-THROWING: an employee whose bank name isn't in
 * the known-bank table yields a {@code "UNKNOWN"} code (and a flagged row) instead of an
 * exception, so one unmapped bank can't fail the whole run's export — the client fills the
 * gap in by hand before uploading. (Contrast {@code EmployeeService#getBankCode}, which
 * throws because it gates Paystack recipient creation at employee-onboarding time.)
 */
@Component
public class PaymentRowMapper {

    /** Bank name → 3-digit NIBSS sort code. Mirrors the table in EmployeeService. */
    private static final Map<String, String> BANK_CODES = Map.ofEntries(
            Map.entry("GTBank", "058"),
            Map.entry("GTB", "058"),
            Map.entry("Guaranty Trust Bank", "058"),
            Map.entry("First Bank", "011"),
            Map.entry("FirstBank", "011"),
            Map.entry("UBA", "033"),
            Map.entry("United Bank For Africa", "033"),
            Map.entry("Access Bank", "044"),
            Map.entry("Access", "044"),
            Map.entry("Zenith Bank", "057"),
            Map.entry("Zenith", "057"),
            Map.entry("Union Bank", "032"),
            Map.entry("Union", "032"),
            Map.entry("FCMB", "214"),
            Map.entry("First City Monument Bank", "214"),
            Map.entry("Stanbic IBTC", "221"),
            Map.entry("Stanbic", "221"),
            Map.entry("Sterling Bank", "232"),
            Map.entry("Sterling", "232"),
            Map.entry("Polaris Bank", "076"),
            Map.entry("Polaris", "076"),
            Map.entry("Ecobank", "050"),
            Map.entry("Eco", "050")
    );

    static final String UNKNOWN_BANK_CODE = "UNKNOWN";

    /**
     * Build the payment row for one entry. A soft-deleted/missing employee yields blank
     * beneficiary fields and an UNKNOWN code rather than throwing — the row is still
     * emitted (and flagged) so the file's totals stay reconcilable against the run.
     */
    public PaymentRow toRow(PayrollEntry entry, String narration) {
        Employee emp = entry.getEmployee();

        String employeeNumber = emp != null && emp.getEmployeeNumber() != null ? emp.getEmployeeNumber() : "";
        String accountName = emp != null && emp.getBankAccountName() != null ? emp.getBankAccountName() : "";
        String accountNumber = emp != null && emp.getBankAccountNumber() != null ? emp.getBankAccountNumber() : "";
        String bankName = emp != null && emp.getBankName() != null ? emp.getBankName() : "";

        String bankCode = resolveBankCode(bankName);
        boolean unknown = UNKNOWN_BANK_CODE.equals(bankCode);

        double amountNaira = (entry.getNetSalary() != null ? entry.getNetSalary() : 0L) / 100.0;

        return new PaymentRow(employeeNumber, accountName, accountNumber, bankName,
                bankCode, amountNaira, narration, unknown);
    }

    /** Resolve a bank name to its sort code; {@code "UNKNOWN"} when unmatched (never throws). */
    public String resolveBankCode(String bankName) {
        if (bankName == null || bankName.isBlank()) {
            return UNKNOWN_BANK_CODE;
        }
        String code = BANK_CODES.get(bankName);
        if (code != null) {
            return code;
        }
        for (Map.Entry<String, String> e : BANK_CODES.entrySet()) {
            if (e.getKey().equalsIgnoreCase(bankName)) {
                return e.getValue();
            }
        }
        return UNKNOWN_BANK_CODE;
    }
}
