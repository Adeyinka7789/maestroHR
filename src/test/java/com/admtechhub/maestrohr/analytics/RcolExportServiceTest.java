package com.admtechhub.maestrohr.analytics;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test for the RCOL CSV/Excel formatting — no Spring, no DB. */
class RcolExportServiceTest {

    private final RcolExportService service = new RcolExportService();

    private RcolReport report() {
        return new RcolReport(true, "July 2026",
                List.of(new RcolReport.Row("Sales", 2, 1_500_000L, 150_000L, 15_000L, 15_000L, 1_680_000L)),
                new RcolReport.Row("TOTAL", 2, 1_500_000L, 150_000L, 15_000L, 15_000L, 1_680_000L));
    }

    @Test
    void toCsv_hasHeaderRowsAndTotals() {
        String csv = new String(service.toCsv(report()), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("Department,Employees,Gross,Employer Pension,NSITF (1%),ITF (1%),Real Cost of Labor");
        // 1,500,000 kobo → 15000.00 naira; RCOL 1,680,000 → 16800.00.
        assertThat(csv).contains("Sales,2,15000.00,1500.00,150.00,150.00,16800.00");
        assertThat(csv).contains("TOTAL,2,");
    }

    @Test
    void toExcel_producesReadableWorkbook() throws Exception {
        byte[] xlsx = service.toExcel(report());
        assertThat(xlsx.length).isGreaterThan(0);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Department");
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("Sales");
            assertThat(first.getCell(6).getNumericCellValue()).isEqualTo(16800.00); // RCOL in naira
        }
    }
}
