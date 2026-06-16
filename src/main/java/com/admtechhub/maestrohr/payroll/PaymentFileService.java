package com.admtechhub.maestrohr.payroll;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Generates the bank payment file (CSV or Excel) for an approved-or-later payroll run.
 * This is a pure READ action — it never changes the run's status. The client downloads the
 * file and uploads it to their own bank; MaestroHR never moves the money and so can't know
 * when payment completes (that is the separate, manual "Mark as Paid" action).
 *
 * Both formats share one column set and one {@link PaymentRowMapper}, so CSV and Excel can
 * never drift apart:
 * <pre>Employee Number | Account Name | Account Number | Bank Name | Bank Code | Amount (NGN) | Narration</pre>
 * The run is looked up tenant-scoped via the {@code @SQLRestriction} on {@link PayrollRun},
 * so a run from another tenant is simply "not found".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentFileService {

    static final String[] COLUMNS = {
            "Employee Number", "Account Name", "Account Number",
            "Bank Name", "Bank Code", "Amount (NGN)", "Narration"
    };

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final PaymentRowMapper paymentRowMapper;

    @Transactional(readOnly = true)
    public byte[] generateCsv(UUID payrollRunId) {
        List<PaymentRow> rows = buildRows(payrollRunId);

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", COLUMNS)).append("\n");
        for (PaymentRow row : rows) {
            sb.append(escape(row.employeeNumber())).append(',')
                    .append(escape(row.accountName())).append(',')
                    .append(escape(row.accountNumber())).append(',')
                    .append(escape(row.bankName())).append(',')
                    .append(escape(row.bankCode())).append(',')
                    .append(String.format("%.2f", row.amountNaira())).append(',')
                    .append(escape(row.narration())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] generateExcel(UUID payrollRunId) {
        List<PaymentRow> rows = buildRows(payrollRunId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Payment File");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (PaymentRow row : rows) {
                Row r = sheet.createRow(rowNum++);
                // Account number kept as text (leading zeros / length must be preserved).
                r.createCell(0).setCellValue(row.employeeNumber());
                r.createCell(1).setCellValue(row.accountName());
                r.createCell(2).setCellValue(row.accountNumber());
                r.createCell(3).setCellValue(row.bankName());
                r.createCell(4).setCellValue(row.bankCode());
                r.createCell(5).setCellValue(row.amountNaira());
                r.createCell(6).setCellValue(row.narration());
            }

            for (int i = 0; i < COLUMNS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate payment-file Excel for payroll run: {}", payrollRunId, e);
            throw new RuntimeException("Failed to generate payment file: " + e.getMessage(), e);
        }
    }

    private List<PaymentRow> buildRows(UUID payrollRunId) {
        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        PayrollStatus status = run.getStatus();
        if (status != PayrollStatus.APPROVED
                && status != PayrollStatus.DISBURSING
                && status != PayrollStatus.COMPLETED) {
            throw new IllegalStateException("Cannot export payment file for a run that has not been approved");
        }

        String narration = "Salary " + run.getPeriod();
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId);
        return entries.stream()
                .map(entry -> paymentRowMapper.toRow(entry, narration))
                .toList();
    }

    /** RFC-4180 CSV quoting: wrap and double-up quotes when the value holds a comma/quote/newline. */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
