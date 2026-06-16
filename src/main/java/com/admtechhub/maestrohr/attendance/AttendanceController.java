package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
@Slf4j
@RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final EmployeeService employeeService;

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayAttendance() {
        LocalDate today = LocalDate.now();
        List<AttendanceRecordDTO> records = attendanceService.getAttendanceByDate(today);

        long presentCount = records.stream().filter(r -> r.getStatus() == AttendanceStatus.PRESENT).count();
        long lateCount = records.stream().filter(r -> r.getStatus() == AttendanceStatus.LATE).count();
        long absentCount = records.stream().filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count();

        List<Map<String, Object>> recordList = records.stream().map(record -> {
            Map<String, Object> map = new HashMap<>();
            map.put("employeeName", record.getEmployeeName());
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

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<AttendanceRecordDTO>>> getAttendanceByDate(@RequestParam LocalDate date) {
        List<AttendanceRecordDTO> records = attendanceService.getAttendanceByDate(date);
        return ResponseEntity.ok(ApiResponse.success("Attendance records retrieved", records));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<AttendanceRecordDTO>>> getEmployeeAttendance(
            @PathVariable UUID employeeId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {

        List<AttendanceRecordDTO> records = attendanceService.getEmployeeAttendance(employeeId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("Employee attendance retrieved", records));
    }

    @GetMapping("/employee/{employeeId}/date")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceRecordDTO>> getEmployeeAttendanceByDate(
            @PathVariable UUID employeeId,
            @RequestParam LocalDate date) {

        AttendanceRecordDTO record = attendanceService.getAttendanceByEmployeeAndDate(employeeId, date);
        if (record == null) {
            return ResponseEntity.ok(ApiResponse.success("No attendance record found for this date", null));
        }
        return ResponseEntity.ok(ApiResponse.success("Attendance record retrieved", record));
    }

    @PostMapping("/mark")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceRecordDTO>> markAttendance(
            @RequestParam UUID employeeId,
            @RequestParam LocalDate date,
            @RequestParam AttendanceStatus status,
            @RequestParam(required = false) String clockInTime,
            @RequestParam(required = false) String clockOutTime,
            @RequestParam(required = false) String notes) {

        AttendanceRecordDTO record = attendanceService.markAttendance(employeeId, date, status, clockInTime, clockOutTime, notes);
        return ResponseEntity.ok(ApiResponse.success("Attendance marked successfully", record));
    }

    @PutMapping("/{recordId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceRecordDTO>> updateAttendance(
            @PathVariable UUID recordId,
            @RequestParam AttendanceStatus status,
            @RequestParam(required = false) String clockInTime,
            @RequestParam(required = false) String clockOutTime) {

        AttendanceRecordDTO record = attendanceService.updateAttendance(recordId, status, clockInTime, clockOutTime);
        return ResponseEntity.ok(ApiResponse.success("Attendance updated successfully", record));
    }

    @GetMapping("/summary/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
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
     * Self check-in for the authenticated employee. The {@code employeeId} param is verified
     * against the caller's own Employee record ({@link #verifyOwnRecord}) — a mismatch is a 403,
     * so a user can only ever check themselves in (admins check others in via {@code /mark}).
     * Guards (double check-in) and notes persistence live in {@link AttendanceService#checkIn};
     * the double-action race throws {@link IllegalStateException}, rendered as a 400 by
     * {@link #handleSelfActionConflict}.
     */
    @PostMapping("/check-in")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'HR_ADMIN', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceRecordDTO>> selfCheckIn(
            @RequestParam UUID employeeId,
            @RequestParam(required = false) String notes,
            @AuthenticationPrincipal UserDetails userDetails) {

        ResponseEntity<ApiResponse<AttendanceRecordDTO>> denied = verifyOwnRecord(employeeId, userDetails);
        if (denied != null) {
            return denied;
        }

        AttendanceRecordDTO record = attendanceService.checkIn(employeeId, notes);
        return ResponseEntity.ok(ApiResponse.success("Checked in successfully", record));
    }

    /**
     * Self check-out for the authenticated employee. Same own-record verification as
     * {@link #selfCheckIn}; guards (no check-in / already checked out) and notes persistence
     * live in {@link AttendanceService#checkOut}.
     */
    @PostMapping("/check-out")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'HR_ADMIN', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<AttendanceRecordDTO>> selfCheckOut(
            @RequestParam UUID employeeId,
            @RequestParam(required = false) String notes,
            @AuthenticationPrincipal UserDetails userDetails) {

        ResponseEntity<ApiResponse<AttendanceRecordDTO>> denied = verifyOwnRecord(employeeId, userDetails);
        if (denied != null) {
            return denied;
        }

        AttendanceRecordDTO updated = attendanceService.checkOut(employeeId, notes);
        return ResponseEntity.ok(ApiResponse.success("Checked out successfully", updated));
    }

    /**
     * Ensures the {@code employeeId} being checked in/out is the caller's own Employee record.
     * Returns a 403 {@link ResponseEntity} when it isn't, or {@code null} when the caller is
     * cleared to proceed. Resolving the caller's own id via {@link EmployeeService#findByEmail}
     * throws {@link IllegalArgumentException} (→ 400) when the authenticated account has no
     * Employee record — i.e. admin/owner accounts can't self check-in; they use {@code /mark}.
     */
    private ResponseEntity<ApiResponse<AttendanceRecordDTO>> verifyOwnRecord(
            UUID employeeId, UserDetails userDetails) {
        UUID ownId = employeeService.findByEmail(userDetails.getUsername()).getId();
        if (!ownId.equals(employeeId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("You can only check in or out for your own employee record"));
        }
        return null;
    }

    /**
     * Renders the self check-in/out double-action race ({@link IllegalStateException} from
     * {@link AttendanceService#checkIn}/{@code checkOut}: "Already checked in/out today",
     * "You haven't checked in today") as a 400 rather than letting it fall through to the
     * generic 500 + Sentry handler in {@code GlobalExceptionHandler}.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleSelfActionConflict(IllegalStateException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @GetMapping("/calendar/{employeeId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonthlyCalendar(
            @PathVariable UUID employeeId,
            @RequestParam int year,
            @RequestParam int month) {

        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);

        List<AttendanceRecordDTO> records = attendanceService.getEmployeeAttendance(employeeId, startDate, endDate);

        Map<String, Object> calendar = new HashMap<>();
        calendar.put("year", year);
        calendar.put("month", month);
        calendar.put("employeeId", employeeId);

        Map<String, String> attendanceMap = new HashMap<>();
        for (AttendanceRecordDTO record : records) {
            attendanceMap.put(record.getAttendanceDate().toString(), record.getStatus().name());
        }
        calendar.put("attendance", attendanceMap);

        long presentDays = attendanceService.getPresentDaysInMonth(employeeId, year, month);
        calendar.put("presentDays", presentDays);
        calendar.put("totalDays", endDate.getDayOfMonth());

        return ResponseEntity.ok(ApiResponse.success("Calendar data retrieved", calendar));
    }
}