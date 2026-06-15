package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Assembles the {@link AttendanceSelfView} for the employee self check-in/out card.
 * Read-only — the actual writes go through {@code AttendanceService.checkIn/checkOut};
 * this only reflects the employee's own record for the current day so the card renders
 * fully populated with no client-side fetch. Mirrors {@link AttendanceListService}.
 *
 * The query is tenant-scoped by the {@code @SQLRestriction} on {@link AttendanceRecord}.
 */
@Service
@RequiredArgsConstructor
public class AttendanceSelfService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public AttendanceSelfView build(UUID employeeId, String employeeName) {
        LocalDate today = LocalDate.now();
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, today)
                .orElse(null);

        boolean checkedIn = record != null && record.getClockInTime() != null;
        boolean checkedOut = record != null && record.getClockOutTime() != null;

        String statusLabel = checkedOut ? "Checked out"
                : (checkedIn ? "Checked in" : "Not checked in yet");
        String statusKind = checkedIn && !checkedOut ? "success" : "neutral";

        return new AttendanceSelfView(
                today.format(DATE_FORMAT),
                employeeName,
                statusLabel,
                statusKind,
                formatTime(record == null ? null : record.getClockInTime()),
                formatTime(record == null ? null : record.getClockOutTime()),
                formatHours(record == null ? null : record.getHoursWorked()),
                record == null ? null : record.getNotes(),
                !checkedIn,                  // canCheckIn  — only when no clock-in yet
                checkedIn && !checkedOut);   // canCheckOut — clocked in, not yet out
    }

    private String formatTime(LocalTime time) {
        return time == null ? "—" : time.format(TIME_FORMAT);
    }

    private String formatHours(BigDecimal hours) {
        return hours == null ? "—" : hours.stripTrailingZeros().toPlainString();
    }
}
