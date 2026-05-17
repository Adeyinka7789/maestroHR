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

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;

    /**
     * Get attendance records for a specific date
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> getAttendanceByDate(LocalDate date) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        return attendanceRepository.findByAttendanceDate(date).stream()
                .filter(record -> record.getTenant().getId().equals(tenantId))
                .toList();
    }

    /**
     * Get attendance for an employee within date range
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> getEmployeeAttendance(UUID employeeId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusMonths(3);
        if (endDate == null) endDate = LocalDate.now();
        return attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, startDate, endDate);
    }

    /**
     * Get attendance for a specific employee on a specific date
     */
    @Transactional(readOnly = true)
    public AttendanceRecord getAttendanceByEmployeeAndDate(UUID employeeId, LocalDate date) {
        return attendanceRepository.findByEmployeeIdAndAttendanceDate(employeeId, date).orElse(null);
    }

    /**
     * Mark attendance for an employee
     */
    @Transactional
    public AttendanceRecord markAttendance(UUID employeeId, LocalDate date, AttendanceStatus status,
                                           String clockInTimeStr, String clockOutTimeStr) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        LocalTime clockIn = clockInTimeStr != null ? LocalTime.parse(clockInTimeStr) : null;
        LocalTime clockOut = clockOutTimeStr != null ? LocalTime.parse(clockOutTimeStr) : null;

        BigDecimal hoursWorked = null;
        if (clockIn != null && clockOut != null) {
            long minutes = ChronoUnit.MINUTES.between(clockIn, clockOut);
            hoursWorked = BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP);
        }

        AttendanceRecord record = AttendanceRecord.builder()
                .tenant(tenant)
                .employee(employee)
                .attendanceDate(date)
                .clockInTime(clockIn)
                .clockOutTime(clockOut)
                .hoursWorked(hoursWorked)
                .status(status)
                .checkInMethod("MANUAL")
                .build();

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Attendance marked for employee {} on {}: {}", employeeId, date, status);

        return saved;
    }

    /**
     * Update attendance record by ID
     */
    @Transactional
    public AttendanceRecord updateAttendance(UUID recordId, AttendanceStatus status,
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

        return updated;
    }

    /**
     * Get monthly attendance summary for an employee
     */
    @Transactional(readOnly = true)
    public long getPresentDaysInMonth(UUID employeeId, int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);
        return attendanceRepository.countPresentDays(employeeId, startDate, endDate);
    }
}