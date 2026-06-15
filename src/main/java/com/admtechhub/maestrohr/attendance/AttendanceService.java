package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;

    // Helper method to convert entity to DTO
    private AttendanceRecordDTO toDto(AttendanceRecord record) {
        if (record == null) return null;
        return new AttendanceRecordDTO(
                record.getId(),
                record.getEmployee().getId(),
                record.getEmployee().getFullName(),
                record.getAttendanceDate(),
                record.getClockInTime(),
                record.getClockOutTime(),
                record.getHoursWorked(),
                record.getStatus(),
                record.getCheckInMethod(),
                record.getNotes()
        );
    }

    // Helper for list conversion
    private List<AttendanceRecordDTO> toDtoList(List<AttendanceRecord> records) {
        return records.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecordDTO> getAttendanceByDate(LocalDate date) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        List<AttendanceRecord> records = attendanceRepository.findByAttendanceDate(date).stream()
                .filter(record -> record.getTenant().getId().equals(tenantId))
                .toList();
        return toDtoList(records);
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecordDTO> getEmployeeAttendance(UUID employeeId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusMonths(3);
        if (endDate == null) endDate = LocalDate.now();
        List<AttendanceRecord> records = attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, startDate, endDate);
        return toDtoList(records);
    }

    @Transactional(readOnly = true)
    public AttendanceRecordDTO getAttendanceByEmployeeAndDate(UUID employeeId, LocalDate date) {
        AttendanceRecord record = attendanceRepository.findByEmployeeIdAndAttendanceDate(employeeId, date).orElse(null);
        return toDto(record);
    }

    @Transactional
    public AttendanceRecordDTO markAttendance(UUID employeeId, LocalDate date, AttendanceStatus status,
                                              String clockInTimeStr, String clockOutTimeStr) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        // Check if attendance already exists for this employee on this date
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, date)
                .orElse(null);

        LocalTime clockIn = clockInTimeStr != null ? LocalTime.parse(clockInTimeStr) : null;
        LocalTime clockOut = clockOutTimeStr != null ? LocalTime.parse(clockOutTimeStr) : null;

        BigDecimal hoursWorked = null;
        if (clockIn != null && clockOut != null) {
            long minutes = ChronoUnit.MINUTES.between(clockIn, clockOut);
            hoursWorked = BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        if (record == null) {
            // Create new record
            record = AttendanceRecord.builder()
                    .tenant(tenant)
                    .employee(employee)
                    .attendanceDate(date)
                    .clockInTime(clockIn)
                    .clockOutTime(clockOut)
                    .hoursWorked(hoursWorked)
                    .status(status)
                    .checkInMethod("MANUAL")
                    .build();
        } else {
            // Update existing record
            record.setStatus(status);
            if (clockIn != null) record.setClockInTime(clockIn);
            if (clockOut != null) record.setClockOutTime(clockOut);
            record.setHoursWorked(hoursWorked);
        }

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Attendance marked/updated for employee {} on {}: {}", employeeId, date, status);
        return toDto(saved);
    }

    @Transactional
    public AttendanceRecordDTO updateAttendance(UUID recordId, AttendanceStatus status,
                                                String clockInTimeStr, String clockOutTimeStr) {
        AttendanceRecord record = attendanceRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Attendance record not found"));

        if (clockInTimeStr != null) record.setClockInTime(LocalTime.parse(clockInTimeStr));
        if (clockOutTimeStr != null) record.setClockOutTime(LocalTime.parse(clockOutTimeStr));

        if (record.getClockInTime() != null && record.getClockOutTime() != null) {
            long minutes = java.time.temporal.ChronoUnit.MINUTES.between(record.getClockInTime(), record.getClockOutTime());
            record.setHoursWorked(java.math.BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP));
        }

        record.setStatus(status);

        AttendanceRecord updated = attendanceRepository.save(record);
        log.info("Attendance record {} updated", recordId);
        return toDto(updated);
    }

    /**
     * Self check-in for the given employee on the current day. Single source of truth for
     * both the server-rendered web flow ({@code AttendanceSelfController}) and the REST
     * endpoint ({@code AttendanceController.selfCheckIn}); the caller is responsible for
     * resolving/authorising {@code employeeId} (the web controller resolves it from the
     * session, the REST controller verifies it matches the authenticated principal).
     *
     * Guards the double check-in race by throwing {@link IllegalStateException} when today's
     * record already has a clock-in — controllers render that in-place (web) or as a 400
     * (REST) rather than creating a duplicate.
     *
     * KNOWN ITEM (intentional, not redesigned here — mirrors {@link #markAttendance} and the
     * negative-balance leave follow-up): when a record already exists for today without a
     * clock-in (e.g. an admin pre-marked the employee ABSENT), self check-in flips the status
     * to PRESENT. That can erase an absent-day payroll deduction. Carried over deliberately;
     * any policy change belongs in a dedicated step.
     */
    @Transactional
    public AttendanceRecordDTO checkIn(UUID employeeId, String notes) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        LocalDate today = LocalDate.now();
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, today)
                .orElse(null);

        if (record != null && record.getClockInTime() != null) {
            throw new IllegalStateException("Already checked in today");
        }

        LocalTime now = LocalTime.now();
        if (record == null) {
            record = AttendanceRecord.builder()
                    .tenant(tenant)
                    .employee(employee)
                    .attendanceDate(today)
                    .clockInTime(now)
                    .status(AttendanceStatus.PRESENT)
                    .checkInMethod("SELF_SERVICE")
                    .build();
        } else {
            // Existing record without a clock-in — see KNOWN ITEM above (flips to PRESENT).
            record.setClockInTime(now);
            record.setStatus(AttendanceStatus.PRESENT);
            record.setCheckInMethod("SELF_SERVICE");
        }
        if (notes != null && !notes.isBlank()) {
            record.setNotes(notes.trim());
        }

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Self check-in for employee {} on {}", employeeId, today);
        return toDto(saved);
    }

    /**
     * Self check-out for the given employee on the current day. Shared by the web and REST
     * flows like {@link #checkIn}. Recomputes {@code hoursWorked} from the recorded clock-in
     * to now. Throws {@link IllegalStateException} when the employee never checked in today
     * or has already checked out (the double-action race), for the controllers to surface.
     */
    @Transactional
    public AttendanceRecordDTO checkOut(UUID employeeId, String notes) {
        LocalDate today = LocalDate.now();
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, today)
                .orElseThrow(() -> new IllegalStateException("You haven't checked in today"));

        if (record.getClockInTime() == null) {
            throw new IllegalStateException("You haven't checked in today");
        }
        if (record.getClockOutTime() != null) {
            throw new IllegalStateException("Already checked out today");
        }

        LocalTime now = LocalTime.now();
        record.setClockOutTime(now);
        long minutes = ChronoUnit.MINUTES.between(record.getClockInTime(), now);
        record.setHoursWorked(BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP));
        if (notes != null && !notes.isBlank()) {
            record.setNotes(notes.trim());
        }

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Self check-out for employee {} on {}", employeeId, today);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public long getPresentDaysInMonth(UUID employeeId, int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);
        return attendanceRepository.countPresentDays(employeeId, startDate, endDate);
    }

    /** Count of ABSENT records in the period — used to calculate attendance deduction in kobo. */
    @Transactional(readOnly = true)
    public int getAbsentDays(UUID employeeId, LocalDate periodStart, LocalDate periodEnd) {
        return (int) attendanceRepository.countAbsentDays(employeeId, periodStart, periodEnd);
    }

    /** Count of LATE records in the period — informational only, no automatic deduction applied. */
    @Transactional(readOnly = true)
    public int getLateDays(UUID employeeId, LocalDate periodStart, LocalDate periodEnd) {
        return (int) attendanceRepository.countLateDays(employeeId, periodStart, periodEnd);
    }
}