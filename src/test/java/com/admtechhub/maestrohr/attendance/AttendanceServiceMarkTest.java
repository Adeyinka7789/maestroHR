package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Step C guards added to {@link AttendanceService#markAttendance} — the
 * payroll-impacting HR/manager write. Collaborators are mocked; the guards are pure in-method
 * logic, so no database is involved. {@link TenantContext} is seeded per-test because the method
 * resolves the tenant from the thread-local context.
 *
 * Covers: #1 future-date rejection, #3 reversed clock times, #5 malformed-time wrapping, and
 * #6 notes persistence. The deferred KNOWN ITEMS (hoursWorked recompute-on-update, checkInMethod
 * not reset) are intentionally NOT asserted here — they await the separate update-semantics step.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceMarkTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private Tenant tenant;
    @Mock private Employee employee;

    @InjectMocks private AttendanceService attendanceService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT_ID.toString());
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(employee.getId()).thenReturn(EMPLOYEE_ID);
        when(employee.getFullName()).thenReturn("Jane Doe");
        when(attendanceRepository.save(any(AttendanceRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── #1 future date ────────────────────────────────────────────────────────────
    @Test
    void markAttendance_futureDate_throwsIllegalState() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                attendanceService.markAttendance(
                        EMPLOYEE_ID, tomorrow, AttendanceStatus.PRESENT, null, null, null));

        assertEquals("Cannot mark attendance for a future date", ex.getMessage());
        verify(attendanceRepository, never()).save(any());
    }

    // ── #3 clock-out not after clock-in ───────────────────────────────────────────
    @Test
    void markAttendance_clockOutBeforeClockIn_throwsIllegalState() {
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(EMPLOYEE_ID, LocalDate.now()))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                attendanceService.markAttendance(
                        EMPLOYEE_ID, LocalDate.now(), AttendanceStatus.PRESENT, "10:00", "09:00", null));

        assertEquals("Clock-out time must be after clock-in time", ex.getMessage());
        verify(attendanceRepository, never()).save(any());
    }

    // ── #5 malformed time → wrapped IllegalArgumentException ──────────────────────
    @Test
    void markAttendance_malformedTime_throwsIllegalArgumentWithWrappedMessage() {
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(EMPLOYEE_ID, LocalDate.now()))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                attendanceService.markAttendance(
                        EMPLOYEE_ID, LocalDate.now(), AttendanceStatus.PRESENT, "not-a-time", null, null));

        assertEquals("Invalid time format (expected HH:mm): not-a-time", ex.getMessage());
        verify(attendanceRepository, never()).save(any());
    }

    // ── #6 notes persisted (trimmed) ──────────────────────────────────────────────
    @Test
    void markAttendance_persistsTrimmedNotes() {
        when(attendanceRepository.findByEmployeeIdAndAttendanceDate(EMPLOYEE_ID, LocalDate.now()))
                .thenReturn(Optional.empty());

        AttendanceRecordDTO dto = attendanceService.markAttendance(
                EMPLOYEE_ID, LocalDate.now(), AttendanceStatus.PRESENT, null, null,
                "  Worked from the Lagos office  ");

        ArgumentCaptor<AttendanceRecord> captor = ArgumentCaptor.forClass(AttendanceRecord.class);
        verify(attendanceRepository).save(captor.capture());
        assertEquals("Worked from the Lagos office", captor.getValue().getNotes());
        assertEquals("Worked from the Lagos office", dto.getNotes());
    }
}
