package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.attendance.device.DeviceAttendanceService;
import com.admtechhub.maestrohr.attendance.device.DeviceAttendanceEventRequest;
import com.admtechhub.maestrohr.attendance.device.DeviceApiKeyRepository;
import com.admtechhub.maestrohr.attendance.device.DeviceEmployeeEnrollmentRepository;
import com.admtechhub.maestrohr.attendance.device.DeviceEventOutcome;
import com.admtechhub.maestrohr.attendance.device.DeviceEventType;
import com.admtechhub.maestrohr.attendance.device.DeviceSyncError;
import com.admtechhub.maestrohr.attendance.device.DeviceSyncErrorRepository;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock EmployeeRepository    employeeRepository;
    @Mock TenantRepository      tenantRepository;
    @Mock ShiftRepository       shiftRepository;
    @Mock AttendancePolicyRepository attendancePolicyRepository;

    // Not @InjectMocks: Clock needs a real instance (an unstubbed @Mock Clock returns null
    // from instant()/getZone(), which NPEs inside LocalTime.now(clock)). Constructed manually
    // in setUp() with a real system clock; Part B/C tests needing an exact instant build their
    // own AttendanceService via serviceWithClock(Clock.fixed(...)) instead of mutating this one.
    AttendanceService attendanceService;

    @Mock DeviceEmployeeEnrollmentRepository enrollmentRepository;
    @Mock DeviceApiKeyRepository             deviceApiKeyRepository;
    @Mock DeviceSyncErrorRepository          syncErrorRepository;
    @Mock AttendanceService                  mockAttendanceService;

    private DeviceAttendanceService deviceAttendanceService;

    private Employee mockEmployee;
    private Tenant   mockTenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        deviceAttendanceService = new DeviceAttendanceService(
                mockAttendanceService,
                enrollmentRepository,
                deviceApiKeyRepository,
                syncErrorRepository,
                tenantRepository,
                new ObjectMapper()
        );

        attendanceService = serviceWithClock(Clock.systemDefaultZone());

        mockEmployee = mock(Employee.class);
        mockTenant = mock(Tenant.class);

        tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());

        when(mockEmployee.getId()).thenReturn(UUID.randomUUID());
        when(mockEmployee.getFullName()).thenReturn("Test Employee");
        when(mockEmployee.getTenant()).thenReturn(mockTenant);

        when(mockTenant.getId()).thenReturn(tenantId);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(mockTenant));
    }

    /** Builds an AttendanceService sharing this test's mocked repositories but with the given Clock. */
    private AttendanceService serviceWithClock(Clock c) {
        return new AttendanceService(
                attendanceRepository, employeeRepository, tenantRepository,
                shiftRepository, attendancePolicyRepository, c);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void checkIn_newRecord_createsRecord() {
        UUID empId = UUID.randomUUID();
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceRecordDTO result = attendanceService.checkIn(empId, null);

        assertNotNull(result);
        assertEquals(AttendanceStatus.PRESENT, result.getStatus());
        assertEquals("Test Employee", result.getEmployeeName());
        verify(attendanceRepository).save(argThat(r ->
                r.getClockInTime() != null && r.getStatus() == AttendanceStatus.PRESENT));
    }

    @Test
    void checkIn_alreadyCheckedIn_throws() {
        UUID empId = UUID.randomUUID();
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));

        AttendanceRecord existing = AttendanceRecord.builder()
                .employee(mockEmployee)
                .attendanceDate(LocalDate.now())
                .clockInTime(LocalTime.now().minusHours(1).withSecond(0).withNano(0))
                .status(AttendanceStatus.PRESENT)
                .build();
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> attendanceService.checkIn(empId, null),
                "Double check-in must throw IllegalStateException");
    }

    @Test
    void checkOut_noCheckIn_throws() {
        UUID empId = UUID.randomUUID();
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> attendanceService.checkOut(empId, null),
                "checkOut with no prior check-in must throw");
    }

    @Test
    void checkOut_alreadyCheckedOut_throws() {
        UUID empId = UUID.randomUUID();
        AttendanceRecord alreadyOut = AttendanceRecord.builder()
                .employee(mockEmployee)
                .attendanceDate(LocalDate.now())
                .clockInTime(LocalTime.now().minusHours(1).withSecond(0).withNano(0))
                .clockOutTime(LocalTime.of(17, 0))
                .status(AttendanceStatus.PRESENT)
                .build();
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.of(alreadyOut));

        assertThrows(IllegalStateException.class, () -> attendanceService.checkOut(empId, null),
                "Double check-out must throw IllegalStateException");
    }

    @Test
    void deviceCheckIn_unknownEnrollment_logsError() {
        UUID deviceKeyId = UUID.randomUUID();
        DeviceAttendanceEventRequest req = new DeviceAttendanceEventRequest();
        req.setEmployeeIdentifier("BIO-UNKNOWN-999");
        req.setEventType(DeviceEventType.CHECK_IN);
        req.setTimestamp(LocalDateTime.now());

        when(enrollmentRepository.findByDeviceApiKeyIdAndDeviceEmployeeId(
                eq(deviceKeyId), eq("BIO-UNKNOWN-999")))
                .thenReturn(Optional.empty());
        when(deviceApiKeyRepository.findById(deviceKeyId)).thenReturn(Optional.empty());

        DeviceEventOutcome outcome = deviceAttendanceService.processEvent(deviceKeyId, req);

        assertEquals(DeviceEventOutcome.Status.EMPLOYEE_NOT_FOUND, outcome.getStatus(),
                "Unknown enrollment must return EMPLOYEE_NOT_FOUND status");
        verify(syncErrorRepository).save(any(DeviceSyncError.class));
        verify(mockAttendanceService, never()).processDeviceCheckIn(any(), any());
    }

    // ── Part B: ABSENT must not be silently overwritten by a later check-in ────────────

    @Test
    void checkIn_whenExistingStatusIsAbsent_doesNotOverwriteStatus() {
        UUID empId = UUID.randomUUID();
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));

        AttendanceRecord absentRecord = AttendanceRecord.builder()
                .tenant(mockTenant)
                .employee(mockEmployee)
                .attendanceDate(LocalDate.now())
                .status(AttendanceStatus.ABSENT)
                .build();
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.of(absentRecord));
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceRecordDTO result = attendanceService.checkIn(empId, null);

        assertEquals(AttendanceStatus.ABSENT, result.getStatus(),
                "Self check-in must not overwrite an HR-set ABSENT status");
        assertNotNull(result.getClockInTime(), "Clock-in time should still be recorded for visibility");
        verify(attendanceRepository).save(argThat(r ->
                r.getStatus() == AttendanceStatus.ABSENT
                        && r.getClockInTime() != null
                        && "SELF_SERVICE".equals(r.getCheckInMethod())));
    }

    @Test
    void processDeviceCheckIn_whenExistingStatusIsAbsent_doesNotOverwriteStatus() {
        UUID empId = UUID.randomUUID();
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));

        AttendanceRecord absentRecord = AttendanceRecord.builder()
                .tenant(mockTenant)
                .employee(mockEmployee)
                .attendanceDate(LocalDate.now())
                .status(AttendanceStatus.ABSENT)
                .build();
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.of(absentRecord));
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime timestamp = LocalDateTime.now().withHour(10).withMinute(30).withSecond(0).withNano(0);
        AttendanceRecordDTO result = attendanceService.processDeviceCheckIn(empId, timestamp);

        assertEquals(AttendanceStatus.ABSENT, result.getStatus(),
                "Biometric check-in must not overwrite an HR-set ABSENT status");
        assertNotNull(result.getClockInTime(), "Clock-in time should still be recorded for visibility");
        verify(attendanceRepository).save(argThat(r ->
                r.getStatus() == AttendanceStatus.ABSENT
                        && r.getClockInTime() != null
                        && "BIOMETRIC".equals(r.getCheckInMethod())));
    }

    // ── Part C: grace-period lateness detection (deterministic via fixed Clock) ────────

    private Shift shiftStartingAt(LocalTime startTime) {
        return Shift.builder().name("Day Shift").startTime(startTime).endTime(startTime.plusHours(8)).build();
    }

    private AttendancePolicy policyWithGrace(int graceMinutes) {
        return AttendancePolicy.builder().name("Standard").gracePeriodMinutes(graceMinutes).build();
    }

    @Test
    void checkIn_atExactShiftStart_isPresent() {
        UUID empId = UUID.randomUUID();
        AttendanceService svc = serviceWithClock(Clock.fixed(Instant.parse("2026-07-03T09:00:00Z"), ZoneOffset.UTC));

        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));
        when(mockEmployee.getShift()).thenReturn(shiftStartingAt(LocalTime.of(9, 0)));
        when(attendancePolicyRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc())
                .thenReturn(Optional.of(policyWithGrace(10)));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceRecordDTO result = svc.checkIn(empId, null);

        assertEquals(AttendanceStatus.PRESENT, result.getStatus());
        assertEquals(LocalTime.of(9, 0), result.getClockInTime());
    }

    @Test
    void checkIn_withinGracePeriod_isPresent() {
        UUID empId = UUID.randomUUID();
        // Shift starts 09:00, grace 10 min -> threshold 09:10. Check in at 09:05 (inside grace).
        AttendanceService svc = serviceWithClock(Clock.fixed(Instant.parse("2026-07-03T09:05:00Z"), ZoneOffset.UTC));

        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));
        when(mockEmployee.getShift()).thenReturn(shiftStartingAt(LocalTime.of(9, 0)));
        when(attendancePolicyRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc())
                .thenReturn(Optional.of(policyWithGrace(10)));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceRecordDTO result = svc.checkIn(empId, null);

        assertEquals(AttendanceStatus.PRESENT, result.getStatus());
    }

    @Test
    void checkIn_oneMinutePastGracePeriod_isLate() {
        UUID empId = UUID.randomUUID();
        // Shift starts 09:00, grace 10 min -> threshold 09:10. Check in at 09:11 (one minute past).
        AttendanceService svc = serviceWithClock(Clock.fixed(Instant.parse("2026-07-03T09:11:00Z"), ZoneOffset.UTC));

        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));
        when(mockEmployee.getShift()).thenReturn(shiftStartingAt(LocalTime.of(9, 0)));
        when(attendancePolicyRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc())
                .thenReturn(Optional.of(policyWithGrace(10)));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceRecordDTO result = svc.checkIn(empId, null);

        assertEquals(AttendanceStatus.LATE, result.getStatus());
    }

    @Test
    void checkIn_noShiftAssignedAndNoTenantDefault_fallsBackToPresent() {
        UUID empId = UUID.randomUUID();
        // Late in the day by clock alone, but with no shift to compare against the pre-existing
        // always-PRESENT behavior must hold.
        AttendanceService svc = serviceWithClock(Clock.fixed(Instant.parse("2026-07-03T15:00:00Z"), ZoneOffset.UTC));

        when(employeeRepository.findById(empId)).thenReturn(Optional.of(mockEmployee));
        when(mockEmployee.getShift()).thenReturn(null);
        when(shiftRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.empty());
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(eq(empId), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AttendanceRecordDTO result = svc.checkIn(empId, null);

        assertEquals(AttendanceStatus.PRESENT, result.getStatus());
    }
}