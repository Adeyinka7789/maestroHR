package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the "export all attendance data" Excel workbook (.xlsx) for the Analytics tab's
 * Export button. Mirrors {@link com.admtechhub.maestrohr.employee.EmployeeService#exportEmployeesToExcel}
 * (Apache POI, bold grey header row, auto-sized columns) so the two exports feel identical.
 *
 * One row per attendance record over the requested date range, tenant-scoped, with optional
 * department and status filters carried straight from the analytics view. The records are
 * fetched with employee (+ department) eagerly joined ({@link AttendanceRepository#findForExport})
 * so writing never triggers a per-row lazy load.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AttendanceExportService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_OF_WEEK = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);

    private static final String[] COLUMNS = {
            "Date", "Day", "Employee Number", "Employee Name", "Department",
            "Clock In", "Clock Out", "Hours Worked", "Status", "Check-in Method", "Notes"
    };

    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public byte[] export(LocalDate startDate, LocalDate endDate, UUID departmentId, AttendanceStatus status) {
        UUID tenantId = currentTenantId();
        List<AttendanceRecord> records =
                attendanceRepository.findForExport(tenantId, startDate, endDate, departmentId, status);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Attendance");

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
            for (AttendanceRecord r : records) {
                Employee e = r.getEmployee();
                Row row = sheet.createRow(rowNum++);
                LocalDate date = r.getAttendanceDate();
                row.createCell(0).setCellValue(date != null ? date.toString() : "");
                row.createCell(1).setCellValue(date != null ? date.format(DAY_OF_WEEK) : "");
                row.createCell(2).setCellValue(e != null && e.getEmployeeNumber() != null ? e.getEmployeeNumber() : "");
                row.createCell(3).setCellValue(e != null ? e.getFullName() : "");
                row.createCell(4).setCellValue(
                        e != null && e.getDepartment() != null ? e.getDepartment().getName() : "");
                row.createCell(5).setCellValue(formatTime(r.getClockInTime()));
                row.createCell(6).setCellValue(formatTime(r.getClockOutTime()));
                row.createCell(7).setCellValue(r.getHoursWorked() != null ? r.getHoursWorked().doubleValue() : 0d);
                row.createCell(8).setCellValue(r.getStatus() != null ? humanize(r.getStatus().name()) : "");
                row.createCell(9).setCellValue(r.getCheckInMethod() != null ? r.getCheckInMethod() : "");
                row.createCell(10).setCellValue(r.getNotes() != null ? r.getNotes() : "");
            }

            for (int i = 0; i < COLUMNS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.error("Attendance Excel export failed: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to generate attendance export", ex);
        }
    }

    private String formatTime(LocalTime time) {
        return time == null ? "" : time.format(TIME);
    }

    /** "HALF_DAY" → "Half Day" (matches the on-screen status labels). */
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

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}
