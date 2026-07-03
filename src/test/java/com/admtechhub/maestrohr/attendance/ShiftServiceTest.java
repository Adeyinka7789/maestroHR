package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftServiceTest {

    @Mock ShiftRepository shiftRepository;
    @Mock TenantRepository tenantRepository;
    @Mock EmployeeRepository employeeRepository;

    @InjectMocks ShiftService shiftService;

    /**
     * The default-shift guard must fire before the existsByShiftId guard (and before any save) —
     * deleting the tenant default would otherwise silently disable lateness detection for every
     * employee currently falling back to it via AttendanceService#getEffectiveShift.
     */
    @Test
    void deleteShift_whenShiftIsDefault_throwsIllegalState() {
        UUID shiftId = UUID.randomUUID();
        Shift defaultShift = Shift.builder()
                .name("Day Shift")
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .isDefault(true)
                .build();
        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(defaultShift));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> shiftService.deleteShift(shiftId));

        assertEquals("Cannot delete the default shift. Set another shift as default first.", ex.getMessage());
        verify(employeeRepository, never()).existsByShiftId(any());
        verify(shiftRepository, never()).save(any());
    }
}
