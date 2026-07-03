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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final ShiftRepository shiftRepository;
    private final AttendancePolicyRepository attendancePolicyRepository;
    private final Clock clock;

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
        List<AttendanceRecord> records = attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, startDate, endDate, currentTenantId());
        return toDtoList(records);
    }

    @Transactional(readOnly = true)
    public AttendanceRecordDTO getAttendanceByEmployeeAndDate(UUID employeeId, LocalDate date) {
        AttendanceRecord record = attendanceRepository.findByEmployeeIdAndAttendanceDate(employeeId, date, currentTenantId()).orElse(null);
        return toDto(record);
    }

    /**
     * HR/manager-driven mark: upserts the attendance record for {@code (employeeId, date)} with the
     * given status and optional clock times + notes. Single source of truth for the REST
     * {@code /api/attendance/mark} endpoint and the server-rendered web form. This is the
     * payroll-impacting write — an ABSENT record here drives the attendance deduction in
     * {@link com.admtechhub.maestrohr.payroll.PayrollEngine}.
     *
     * Guards (Step C): rejects a future date and a clock-out that isn't after the clock-in
     * ({@link IllegalStateException}), and surfaces a malformed time string as
     * {@link IllegalArgumentException} (via {@link #parseTime}) rather than a 500.
     *
     * KNOWN ITEMS (deferred, same treatment as the {@link #checkIn} flip-to-PRESENT and the
     * negative-balance leave follow-up — they need a deliberate redesign of the update semantics,
     * not a Step C bundle):
     *   - on UPDATE, {@code hoursWorked} is recomputed from THIS call's times only, so re-marking
     *     an existing record with no times nulls out previously-computed hours while leaving the
     *     stored clock times in place;
     *   - on UPDATE, {@code checkInMethod} is not reset, so a MANUAL overwrite of a SELF_SERVICE
     *     record keeps reporting SELF_SERVICE.
     */
    @Transactional
    public AttendanceRecordDTO markAttendance(UUID employeeId, LocalDate date, AttendanceStatus status,
                                              String clockInTimeStr, String clockOutTimeStr, String notes) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        // Guard #1: attendance can only be recorded for today or a past day.
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalStateException("Cannot mark attendance for a future date");
        }

        // Check if attendance already exists for this employee on this date
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, date, currentTenantId())
                .orElse(null);

        LocalTime clockIn = parseTime(clockInTimeStr);
        LocalTime clockOut = parseTime(clockOutTimeStr);

        // Guard #3: when both times are supplied, clock-out must be strictly after clock-in.
        if (clockIn != null && clockOut != null && !clockOut.isAfter(clockIn)) {
            throw new IllegalStateException("Clock-out time must be after clock-in time");
        }

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
            // Update existing record (see KNOWN ITEMS: hoursWorked recompute + checkInMethod).
            record.setStatus(status);
            if (clockIn != null) record.setClockInTime(clockIn);
            if (clockOut != null) record.setClockOutTime(clockOut);
            record.setHoursWorked(hoursWorked);
        }

        // Guard #6: persist optional notes, blank-safe and trimmed (mirrors checkIn/checkOut;
        // a blank value leaves any existing note untouched rather than clearing it).
        if (notes != null && !notes.isBlank()) {
            record.setNotes(notes.trim());
        }

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Attendance marked/updated for employee {} on {}: {}", employeeId, date, status);
        return toDto(saved);
    }

    /**
     * Parses an "HH:mm" clock-time param, surfacing a malformed value as an
     * {@link IllegalArgumentException} (→ 400) instead of letting the raw
     * {@link DateTimeParseException} fall through to the generic 500 handler. Null/blank → null.
     */
    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid time format (expected HH:mm): " + value);
        }
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
     * Resolves the effective shift for an employee: {@code employee.getShift()} → the
     * tenant's default shift (RLS-scoped via {@link ShiftRepository#findFirstByIsDefaultTrue})
     * → empty when neither exists. Mirrors {@code LoanPolicyService.getPolicyForEmployee}'s shape.
     */
    @Transactional(readOnly = true)
    public Optional<Shift> getEffectiveShift(Employee employee) {
        if (employee != null && employee.getShift() != null) {
            return Optional.of(employee.getShift());
        }
        return shiftRepository.findFirstByIsDefaultTrue();
    }

    /**
     * Resolves the effective attendance policy for an employee: pay-grade-linked policy (if
     * active) → first active tenant policy → empty. Exact mirror of
     * {@code LoanPolicyService.getPolicyForEmployee}'s resolution order.
     */
    @Transactional(readOnly = true)
    public Optional<AttendancePolicy> getEffectivePolicy(Employee employee) {
        if (employee != null && employee.getPayGrade() != null) {
            AttendancePolicy gradePolicy = employee.getPayGrade().getAttendancePolicy();
            if (gradePolicy != null && Boolean.TRUE.equals(gradePolicy.getIsActive())) {
                return Optional.of(gradePolicy);
            }
        }
        return attendancePolicyRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc();
    }

    /**
     * PRESENT/LATE determination for a check-in. Without a resolvable shift there is nothing
     * to compare the clock-in against, so this keeps the pre-existing always-PRESENT behavior.
     * With a shift, the grace period comes from the effective {@link AttendancePolicy}
     * (0 minutes when no policy resolves) and lateness is a strict "after threshold" comparison
     * — a clock-in exactly at the threshold still counts as on time.
     */
    private AttendanceStatus resolveCheckInStatus(Employee employee, LocalTime clockInTime) {
        Optional<Shift> shift = getEffectiveShift(employee);
        if (shift.isEmpty()) {
            return AttendanceStatus.PRESENT;
        }
        int graceMinutes = getEffectivePolicy(employee)
                .map(AttendancePolicy::getGracePeriodMinutes)
                .orElse(0);
        LocalTime threshold = shift.get().getStartTime().plusMinutes(graceMinutes);
        return clockInTime.isAfter(threshold) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
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
     * ABSENT PRESERVATION: when a record already exists for today with status ABSENT (HR
     * pre-marked the day), self check-in records the clock-in time and method for visibility
     * but does NOT flip the status away from ABSENT — only an explicit HR action via
     * {@link #markAttendance} can do that. For every other case (new record or an existing
     * non-ABSENT record), status is resolved via {@link #resolveCheckInStatus}.
     */
    @Transactional
    public AttendanceRecordDTO checkIn(UUID employeeId, String notes) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        LocalDate today = LocalDate.now(clock);
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, today, currentTenantId())
                .orElse(null);

        if (record != null && record.getClockInTime() != null) {
            throw new IllegalStateException("Already checked in today");
        }

        LocalTime now = LocalTime.now(clock);
        if (record == null) {
            record = AttendanceRecord.builder()
                    .tenant(tenant)
                    .employee(employee)
                    .attendanceDate(today)
                    .clockInTime(now)
                    .status(resolveCheckInStatus(employee, now))
                    .checkInMethod("SELF_SERVICE")
                    .build();
        } else if (record.getStatus() == AttendanceStatus.ABSENT) {
            record.setClockInTime(now);
            record.setCheckInMethod("SELF_SERVICE");
        } else {
            record.setClockInTime(now);
            record.setStatus(resolveCheckInStatus(employee, now));
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
        LocalDate today = LocalDate.now(clock);
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, today, currentTenantId())
                .orElseThrow(() -> new IllegalStateException("You haven't checked in today"));

        if (record.getClockInTime() == null) {
            throw new IllegalStateException("You haven't checked in today");
        }
        if (record.getClockOutTime() != null) {
            throw new IllegalStateException("Already checked out today");
        }

        LocalTime now = LocalTime.now(clock);
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
        return attendanceRepository.countPresentDays(employeeId, startDate, endDate, currentTenantId());
    }

    /**
     * Biometric device check-in for the given employee at a caller-supplied timestamp (which may
     * be a past time when the device is pushing offline records). Guards are the same as
     * {@link #checkIn}: throws {@link IllegalStateException} on double check-in so the device
     * service can classify the outcome as DUPLICATE rather than ERROR. Applies the same
     * ABSENT-preservation rule and grace-period lateness resolution as {@link #checkIn} — see
     * {@link #resolveCheckInStatus}.
     */
    @Transactional
    public AttendanceRecordDTO processDeviceCheckIn(UUID employeeId, LocalDateTime timestamp) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        LocalDate date = timestamp.toLocalDate();
        if (date.isAfter(LocalDate.now(clock))) {
            throw new IllegalStateException("Cannot record attendance for a future date");
        }

        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, date, currentTenantId())
                .orElse(null);

        if (record != null && record.getClockInTime() != null) {
            throw new IllegalStateException("Already checked in for " + date);
        }

        LocalTime clockIn = timestamp.toLocalTime();
        if (record == null) {
            record = AttendanceRecord.builder()
                    .tenant(tenant)
                    .employee(employee)
                    .attendanceDate(date)
                    .clockInTime(clockIn)
                    .status(resolveCheckInStatus(employee, clockIn))
                    .checkInMethod("BIOMETRIC")
                    .build();
        } else if (record.getStatus() == AttendanceStatus.ABSENT) {
            record.setClockInTime(clockIn);
            record.setCheckInMethod("BIOMETRIC");
        } else {
            record.setClockInTime(clockIn);
            record.setStatus(resolveCheckInStatus(employee, clockIn));
            record.setCheckInMethod("BIOMETRIC");
        }

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Biometric check-in: employee={} date={} time={}", employeeId, date, clockIn);
        return toDto(saved);
    }

    /**
     * Biometric device check-out for the given employee at a caller-supplied timestamp.
     * Throws {@link IllegalStateException} when no check-in exists or already checked out
     * (DUPLICATE on the device service side).
     */
    @Transactional
    public AttendanceRecordDTO processDeviceCheckOut(UUID employeeId, LocalDateTime timestamp) {
        LocalDate date = timestamp.toLocalDate();
        AttendanceRecord record = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, date, currentTenantId())
                .orElseThrow(() -> new IllegalStateException("No check-in found for " + date));

        if (record.getClockInTime() == null) {
            throw new IllegalStateException("No check-in found for " + date);
        }
        if (record.getClockOutTime() != null) {
            throw new IllegalStateException("Already checked out for " + date);
        }

        LocalTime clockOut = timestamp.toLocalTime();
        record.setClockOutTime(clockOut);
        long minutes = ChronoUnit.MINUTES.between(record.getClockInTime(), clockOut);
        record.setHoursWorked(BigDecimal.valueOf(minutes / 60.0).setScale(2, java.math.RoundingMode.HALF_UP));

        AttendanceRecord saved = attendanceRepository.save(record);
        log.info("Biometric check-out: employee={} date={} time={}", employeeId, date, clockOut);
        return toDto(saved);
    }

    /** Count of ABSENT records in the period — used to calculate attendance deduction in kobo. */
    @Transactional(readOnly = true)
    public int getAbsentDays(UUID employeeId, LocalDate periodStart, LocalDate periodEnd) {
        return (int) attendanceRepository.countAbsentDays(employeeId, periodStart, periodEnd, currentTenantId());
    }

    /** Count of LATE records in the period — informational only, no automatic deduction applied. */
    @Transactional(readOnly = true)
    public int getLateDays(UUID employeeId, LocalDate periodStart, LocalDate periodEnd) {
        return (int) attendanceRepository.countLateDays(employeeId, periodStart, periodEnd, currentTenantId());
    }

    /**
     * Batch version: returns absent day counts for multiple employees.
     * Single query round-trip instead of N individual calls.
     *
     * @return Map of employeeId → count of ABSENT days in the period
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> getAbsentDaysBatch(List<UUID> employeeIds,
                                                 LocalDate periodStart,
                                                 LocalDate periodEnd) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> result = new java.util.HashMap<>();
        for (UUID id : employeeIds) {
            result.put(id, 0);
        }

        List<List<UUID>> partitions = com.google.common.collect.Lists.partition(employeeIds, 500);

        for (List<UUID> partition : partitions) {
            List<Object[]> results = attendanceRepository
                    .countAbsentDaysBatch(partition, periodStart, periodEnd);

            for (Object[] row : results) {
                UUID empId = (UUID) row[0];
                Long count = (Long) row[1];
                result.put(empId, count != null ? count.intValue() : 0);
            }
        }

        return result;
    }

    @Transactional(readOnly = true)
    public Map<UUID, Integer> getLateDaysBatch(List<UUID> employeeIds,
                                               LocalDate periodStart,
                                               LocalDate periodEnd) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> result = new java.util.HashMap<>();
        for (UUID id : employeeIds) {
            result.put(id, 0);
        }

        List<List<UUID>> partitions = com.google.common.collect.Lists.partition(employeeIds, 500);

        for (List<UUID> partition : partitions) {
            List<Object[]> results = attendanceRepository
                    .countLateDaysBatch(partition, periodStart, periodEnd);

            for (Object[] row : results) {
                UUID empId = (UUID) row[0];
                Long count = (Long) row[1];
                result.put(empId, count != null ? count.intValue() : 0);
            }
        }

        return result;
    }

    private UUID currentTenantId() {
        return UUID.fromString(com.admtechhub.maestrohr.auth.TenantContext.getCurrentTenant());
    }
}
