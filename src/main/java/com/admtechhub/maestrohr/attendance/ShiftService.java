package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for {@link Shift}. Resolution (which shift applies to a given employee) is NOT here —
 * that already lives in {@link AttendanceService#getEffectiveShift}. This service only manages
 * the shift rows themselves, plus the tenant-wide "exactly one default shift" invariant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final TenantRepository tenantRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public Shift createShift(ShiftRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("Shift name is required.");
        }
        LocalTime start = parseTime(req.getStartTime());
        LocalTime end = parseTime(req.getEndTime());
        if (start == null || end == null) {
            throw new IllegalArgumentException("Start time and end time are both required.");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("End time must be after start time.");
        }

        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));

        boolean makeDefault = Boolean.TRUE.equals(req.getIsDefault());
        if (makeDefault) {
            unsetExistingDefault(null);
        }

        Shift shift = Shift.builder()
                .tenant(tenant)
                .name(req.getName().trim())
                .startTime(start)
                .endTime(end)
                .isDefault(makeDefault)
                .build();

        Shift saved = shiftRepository.save(shift);
        log.info("Shift {} created for tenant {}", saved.getId(), tenantId);
        return saved;
    }

    @Transactional
    public Shift updateShift(UUID id, ShiftRequest req) {
        Shift shift = requireShift(id);

        if (req.getName() != null && !req.getName().isBlank()) {
            shift.setName(req.getName().trim());
        }

        LocalTime start = parseTime(req.getStartTime());
        LocalTime end = parseTime(req.getEndTime());
        LocalTime effectiveStart = start != null ? start : shift.getStartTime();
        LocalTime effectiveEnd = end != null ? end : shift.getEndTime();
        if (!effectiveEnd.isAfter(effectiveStart)) {
            throw new IllegalArgumentException("End time must be after start time.");
        }
        shift.setStartTime(effectiveStart);
        shift.setEndTime(effectiveEnd);

        boolean makeDefault = Boolean.TRUE.equals(req.getIsDefault());
        if (makeDefault && !shift.isDefault()) {
            unsetExistingDefault(id);
        }
        shift.setDefault(makeDefault);

        return shiftRepository.save(shift);
    }

    @Transactional
    public void deleteShift(UUID id) {
        Shift shift = requireShift(id);
        if (shift.isDefault()) {
            throw new IllegalStateException(
                    "Cannot delete the default shift. Set another shift as default first.");
        }
        if (employeeRepository.existsByShiftId(id)) {
            throw new IllegalStateException(
                    "This shift is assigned to employees and cannot be deleted. Reassign them to another shift first.");
        }
        shift.setDeletedAt(OffsetDateTime.now());
        shiftRepository.save(shift);
        log.info("Soft-deleted shift: {}", id);
    }

    @Transactional(readOnly = true)
    public List<Shift> getShiftsForTenant() {
        return shiftRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Shift getShift(UUID id) {
        return requireShift(id);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Enforces "exactly one default shift per tenant": un-sets whichever shift currently
     * holds {@code isDefault = true} (if any, and if it isn't {@code excludeId} — the row
     * being updated already holds the flag) in the same transaction as the caller's save.
     * No existing "singleton flag" pattern was found elsewhere in the codebase to mirror
     * (grepped for isDefault/isPrimary/setDefault); this is a direct fetch-then-unset,
     * safe here because tenant admin writes are low-concurrency.
     */
    private void unsetExistingDefault(UUID excludeId) {
        shiftRepository.findFirstByIsDefaultTrue().ifPresent(existing -> {
            if (excludeId == null || !existing.getId().equals(excludeId)) {
                existing.setDefault(false);
                shiftRepository.save(existing);
            }
        });
    }

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

    private Shift requireShift(UUID id) {
        return shiftRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Shift not found: " + id));
    }
}
