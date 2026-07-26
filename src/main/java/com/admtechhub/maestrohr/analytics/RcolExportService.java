package com.admtechhub.maestrohr.analytics;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Renders a {@link RcolReport} as CSV or Excel for the finance team. Money is emitted as plain
 * naira with two decimals (kobo/100) — no currency symbol or thousands grouping — so the columns
 * import cleanly as numbers into a spreadsheet / ledger.
 */
@Service
public class RcolExportService {

    private static final String[] HEADERS = {
            "Department", "Employees", "Gross", "Employer Pension", "NSITF (1%)", "ITF (1%)", "Real Cost of Labor"
    };

    public byte[] toCsv(RcolReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append('\n');
        for (RcolReport.Row r : report.rows()) {
            appendCsvRow(sb, r);
        }
        if (report.totals() != null) {
            appendCsvRow(sb, report.totals());
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toExcel(RcolReport report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("RCOL " + report.periodLabel());

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle totalStyle = workbook.createCellStyle();
            Font totalFont = workbook.createFont();
            totalFont.setBold(true);
            totalStyle.setFont(totalFont);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (RcolReport.Row r : report.rows()) {
                writeExcelRow(sheet.createRow(rowNum++), r, null);
            }
            if (report.totals() != null) {
                writeExcelRow(sheet.createRow(rowNum), report.totals(), totalStyle);
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RCOL Excel export", e);
        }
    }

    public String fileName(RcolReport report, String extension) {
        String period = report.periodLabel().isBlank() ? "report"
                : report.periodLabel().toLowerCase(Locale.ENGLISH).replace(' ', '-');
        return "rcol-" + period + "." + extension;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void appendCsvRow(StringBuilder sb, RcolReport.Row r) {
        sb.append(csv(r.department())).append(',')
                .append(r.headcount()).append(',')
                .append(naira(r.grossKobo())).append(',')
                .append(naira(r.employerPensionKobo())).append(',')
                .append(naira(r.nsitfKobo())).append(',')
                .append(naira(r.itfKobo())).append(',')
                .append(naira(r.rcolKobo())).append('\n');
    }

    private void writeExcelRow(Row row, RcolReport.Row r, CellStyle style) {
        Cell[] cells = {
                cell(row, 0, r.department()),
                cell(row, 1, r.headcount()),
                cell(row, 2, r.grossKobo() / 100.0),
                cell(row, 3, r.employerPensionKobo() / 100.0),
                cell(row, 4, r.nsitfKobo() / 100.0),
                cell(row, 5, r.itfKobo() / 100.0),
                cell(row, 6, r.rcolKobo() / 100.0)
        };
        if (style != null) {
            for (Cell c : cells) {
                c.setCellStyle(style);
            }
        }
    }

    private Cell cell(Row row, int i, String v) {
        Cell c = row.createCell(i);
        c.setCellValue(v);
        return c;
    }

    private Cell cell(Row row, int i, double v) {
        Cell c = row.createCell(i);
        c.setCellValue(v);
        return c;
    }

    private String naira(long kobo) {
        return String.format(Locale.ENGLISH, "%.2f", kobo / 100.0);
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
