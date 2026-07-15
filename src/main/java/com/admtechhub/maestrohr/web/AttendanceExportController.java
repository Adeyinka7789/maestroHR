package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Excel download for "export all attendance data", triggered by the Export button on the
 * Analytics tab. This is a plain browser navigation (an {@code <a href download>}), NOT an
 * HTMX swap, so it can safely return a real file response — and a role denial here yields a
 * normal 403 that never reaches layout.js's HTMX responseError handler.
 *
 * The link carries the analytics view's active month ({@code from}/{@code to}) and optional
 * {@code deptId} / {@code status}, so "export" always matches what the user is looking at.
 * Missing/invalid dates fall back to the current month (mirrors the sibling routes' lenient
 * param parsing), and the range is clamped so a hand-edited {@code to < from} can't produce an
 * empty or reversed query.
 *
 * ACCESS: {@code @PreAuthorize} with the same admin/manager roles as the analytics read route.
 * Unlike the HTMX fragments (which catch AccessDeniedException to render an in-place banner),
 * a download is a top-level navigation, so a plain 403 is the right outcome.
 */
@Controller
@RequiredArgsConstructor
public class AttendanceExportController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AttendanceExportService attendanceExportService;

    @GetMapping("/htmx/attendance/export")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "deptId", required = false) String deptId,
            @RequestParam(value = "status", required = false) String status) {

        YearMonth currentMonth = YearMonth.now();
        LocalDate start = parseDate(from, currentMonth.atDay(1));
        LocalDate end = parseDate(to, currentMonth.atEndOfMonth());
        if (end.isBefore(start)) {
            end = start; // guard a hand-edited reversed range rather than returning nothing
        }

        byte[] xlsx = attendanceExportService.export(
                start, end, parseDepartmentId(deptId), parseStatus(status));

        String filename = "attendance-" + start + "-to-" + end + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_MIME));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(xlsx);
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private UUID parseDepartmentId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AttendanceStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AttendanceStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
