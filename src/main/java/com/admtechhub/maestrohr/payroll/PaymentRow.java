package com.admtechhub.maestrohr.payroll;

/**
 * One beneficiary line in a bank payment file (the CSV/Excel a client uploads to their
 * own bank to pay salaries). Built from a {@link PayrollEntry} by {@link PaymentRowMapper};
 * money is already converted from kobo to naira so the file writers stay logic-free.
 *
 * {@code bankCode} is the 3-digit NIBSS sort code resolved from the employee's bank name;
 * when the name isn't in the known-bank table it is the literal {@code "UNKNOWN"} and
 * {@link #bankCodeUnknown()} is true so a single unmapped bank flags its row rather than
 * failing the whole export.
 */
public record PaymentRow(
        String employeeNumber,
        String accountName,
        String accountNumber,
        String bankName,
        String bankCode,
        double amountNaira,
        String narration,
        boolean bankCodeUnknown
) {}
