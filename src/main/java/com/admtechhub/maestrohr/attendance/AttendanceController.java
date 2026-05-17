package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
@Slf4j
public class AttendanceController {

    private final AttendanceService attendanceService;

    /**
     * Get today's attendance summary
     * GET /api/attendance/today
     */
    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayAttendance() {
        LocalDate today = LocalDate.now();
        List<AttendanceRecord> records = attendanceService.getAttendanceByDate(today);

        long presentCount = records.stream().filter(r -> r.getStatus() == AttendanceStatus.PRESENT).count();
        long lateCount = records.stream().filter(r -> r.getStatus() == AttendanceStatus.LATE).count();
        long absentCount = records.stream().filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count();

        List<Map<String, Object>> recordList = records.stream().map(record -> {
            Map<String, Object> map = new HashMap<>();
            // Safe access - use getFullName() which doesn't trigger lazy loading
            map.put("employeeName", record.getEmployee().getFullName());
            map.put("clockInTime", record.getClockInTime() != null ? record.getClockInTime().toString() : null);
            map.put("clockOutTime", record.getClockOutTime() != null ? record.getClockOutTime().toString() : null);
            map.put("hoursWorked", record.getHoursWorked());
            map.put("status", record.getStatus().name());
            return map;
        }).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("presentCount", presentCount);
        response.put("lateCount", lateCount);
        response.put("absentCount", absentCount);
        response.put("records", recordList);

        return ResponseEntity.ok(ApiResponse.success("Attendance data retrieved", response));
    }

    /**
     * Get attendance for a specific date
     * GET /api/attendance?date=2026-05-15
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<List<AttendanceRecord>>> getAttendanceByDate(@RequestParam LocalDate date) {
        List<AttendanceRecord> records = attendanceService.getAttendanceByDate(date);
        return ResponseEntity.ok(ApiResponse.success("Attendance records retrieved", records));
    }

    /**
     * Get attendance for an employee
     * GET /api/attendance/employee/{employeeId}
     */
    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<List<AttendanceRecord>>> getEmployeeAttendance(
            @PathVariable UUID employeeId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {

        List<AttendanceRecord> records = attendanceService.getEmployeeAttendance(employeeId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("Employee attendance retrieved", records));
    }

    /**
     * Get attendance for a specific employee on a specific date
     * GET /api/attendance/employee/{employeeId}/date?date=2026-05-15
     */
    @GetMapping("/employee/{employeeId}/date")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<AttendanceRecord>> getEmployeeAttendanceByDate(
            @PathVariable UUID employeeId,
            @RequestParam LocalDate date) {

        AttendanceRecord record = attendanceService.getAttendanceByEmployeeAndDate(employeeId, date);
        if (record == null) {
            return ResponseEntity.ok(ApiResponse.success("No attendance record found for this date", null));
        }
        return ResponseEntity.ok(ApiResponse.success("Attendance record retrieved", record));
    }

    /**
     * Mark attendance
     * POST /api/attendance/mark
     */
    @PostMapping("/mark")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<AttendanceRecord>> markAttendance(
            @RequestParam UUID employeeId,
            @RequestParam LocalDate date,
            @RequestParam AttendanceStatus status,
            @RequestParam(required = false) String clockInTime,
            @RequestParam(required = false) String clockOutTime) {

        AttendanceRecord record = attendanceService.markAttendance(employeeId, date, status, clockInTime, clockOutTime);
        return ResponseEntity.ok(ApiResponse.success("Attendance marked successfully", record));
    }

    /**
     * Update attendance record
     * PUT /api/attendance/{recordId}
     */
    @PutMapping("/{recordId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<AttendanceRecord>> updateAttendance(
            @PathVariable UUID recordId,
            @RequestParam AttendanceStatus status,
            @RequestParam(required = false) String clockInTime,
            @RequestParam(required = false) String clockOutTime) {

        AttendanceRecord record = attendanceService.updateAttendance(recordId, status, clockInTime, clockOutTime);
        return ResponseEntity.ok(ApiResponse.success("Attendance updated successfully", record));
    }

    /**
     * Get monthly summary for an employee
     * GET /api/attendance/summary/{employeeId}?year=2026&month=5
     */
    @GetMapping("/summary/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonthlySummary(
            @PathVariable UUID employeeId,
            @RequestParam int year,
            @RequestParam int month) {

        long presentDays = attendanceService.getPresentDaysInMonth(employeeId, year, month);

        Map<String, Object> summary = new HashMap<>();
        summary.put("employeeId", employeeId);
        summary.put("year", year);
        summary.put("month", month);
        summary.put("presentDays", presentDays);

        return ResponseEntity.ok(ApiResponse.success("Monthly summary retrieved", summary));
    }

    /**
     * Employee self check-in
     * POST /api/attendance/check-in
     */
    @PostMapping("/check-in")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<AttendanceRecord>> selfCheckIn(
            @RequestParam UUID employeeId,
            @RequestParam(required = false) String notes) {

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        // Check if already checked in today
        AttendanceRecord existing = attendanceService.getAttendanceByEmployeeAndDate(employeeId, today);
        if (existing != null && existing.getClockInTime() != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Already checked in today"));
        }

        AttendanceRecord record = attendanceService.markAttendance(
                employeeId, today, AttendanceStatus.PRESENT, now.toString(), null);

        if (notes != null) {
            record.setNotes(notes);
            attendanceService.updateAttendance(record.getId(), record.getStatus(),
                    record.getClockInTime() != null ? record.getClockInTime().toString() : null,
                    record.getClockOutTime() != null ? record.getClockOutTime().toString() : null);
        }

        return ResponseEntity.ok(ApiResponse.success("Checked in successfully", record));
    }

    /**
     * Employee self check-out
     * POST /api/attendance/check-out
     */
    @PostMapping("/check-out")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'HR_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<AttendanceRecord>> selfCheckOut(
            @RequestParam UUID employeeId,
            @RequestParam(required = false) String notes) {

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        AttendanceRecord record = attendanceService.getAttendanceByEmployeeAndDate(employeeId, today);
        if (record == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No check-in record found for today"));
        }

        if (record.getClockOutTime() != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Already checked out today"));
        }

        record.setClockOutTime(now);
        // Calculate hours worked
        if (record.getClockInTime() != null) {
            long minutes = java.time.temporal.ChronoUnit.MINUTES.between(record.getClockInTime(), now);
            record.setHoursWorked(java.math.BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP));
        }

        if (notes != null) {
            record.setNotes(notes);
        }

        AttendanceRecord updated = attendanceService.updateAttendance(record.getId(), record.getStatus(),
                record.getClockInTime() != null ? record.getClockInTime().toString() : null,
                now.toString());

        return ResponseEntity.ok(ApiResponse.success("Checked out successfully", updated));
    }

    /**
     * Get monthly calendar view for an employee
     * GET /api/attendance/calendar/{employeeId}?year=2026&month=5
     */
    @GetMapping("/calendar/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonthlyCalendar(
            @PathVariable UUID employeeId,
            @RequestParam int year,
            @RequestParam int month) {

        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);

        List<AttendanceRecord> records = attendanceService.getEmployeeAttendance(employeeId, startDate, endDate);

        // Build calendar data
        Map<String, Object> calendar = new HashMap<>();
        calendar.put("year", year);
        calendar.put("month", month);
        calendar.put("employeeId", employeeId);

        // Map date -> attendance status
        Map<String, String> attendanceMap = new HashMap<>();
        for (AttendanceRecord record : records) {
            attendanceMap.put(record.getAttendanceDate().toString(), record.getStatus().name());
        }
        calendar.put("attendance", attendanceMap);

        // Get monthly summary
        long presentDays = attendanceService.getPresentDaysInMonth(employeeId, year, month);
        calendar.put("presentDays", presentDays);
        calendar.put("totalDays", endDate.getDayOfMonth());

        return ResponseEntity.ok(ApiResponse.success("Calendar data retrieved", calendar));
    }
}