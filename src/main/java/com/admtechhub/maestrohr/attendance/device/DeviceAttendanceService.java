package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.attendance.AttendanceRecordDTO;
import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceAttendanceService {

    private final AttendanceService attendanceService;
    private final DeviceEmployeeEnrollmentRepository enrollmentRepository;
    private final DeviceApiKeyRepository deviceApiKeyRepository;
    private final DeviceSyncErrorRepository syncErrorRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    /**
     * Processes a single attendance event pushed by a device.
     *
     * <p>On enrollment miss: logs to {@code device_sync_errors} so HR can add the mapping.
     * On duplicate (already checked in/out for that date): classified as DUPLICATE, not an error.
     * Errors are per-event so a bad record doesn't fail the whole batch.
     */
    @Transactional
    public DeviceEventOutcome processEvent(UUID deviceApiKeyId, DeviceAttendanceEventRequest req) {
        String raw = toJson(req);
        LocalDateTime timestamp = req.getTimestamp() != null ? req.getTimestamp() : LocalDateTime.now();

        Optional<DeviceEmployeeEnrollment> enrollmentOpt =
                enrollmentRepository.findByDeviceApiKeyIdAndDeviceEmployeeId(
                        deviceApiKeyId, req.getEmployeeIdentifier());

        if (enrollmentOpt.isEmpty()) {
            logSyncError(deviceApiKeyId, req, "EMPLOYEE_NOT_ENROLLED",
                    "No enrollment found for device employee ID: " + req.getEmployeeIdentifier(), raw);
            return new DeviceEventOutcome(
                    req.getEmployeeIdentifier(),
                    req.getEventType() != null ? req.getEventType().name() : null,
                    DeviceEventOutcome.Status.EMPLOYEE_NOT_FOUND,
                    "No enrollment found for device employee ID: " + req.getEmployeeIdentifier(),
                    null);
        }

        UUID employeeId = enrollmentOpt.get().getEmployee().getId();

        try {
            AttendanceRecordDTO record = dispatchEvent(req.getEventType(), employeeId, timestamp);
            return new DeviceEventOutcome(
                    req.getEmployeeIdentifier(),
                    req.getEventType().name(),
                    DeviceEventOutcome.Status.ACCEPTED,
                    null,
                    record);

        } catch (IllegalStateException e) {
            // Already checked in/out → DUPLICATE (device re-pushed an already-processed record)
            return new DeviceEventOutcome(
                    req.getEmployeeIdentifier(),
                    req.getEventType() != null ? req.getEventType().name() : null,
                    DeviceEventOutcome.Status.DUPLICATE,
                    e.getMessage(),
                    null);

        } catch (Exception e) {
            log.error("Unexpected error processing device event for employee={}: {}", employeeId, e.getMessage(), e);
            logSyncError(deviceApiKeyId, req, "PROCESSING_ERROR", e.getMessage(), raw);
            return new DeviceEventOutcome(
                    req.getEmployeeIdentifier(),
                    req.getEventType() != null ? req.getEventType().name() : null,
                    DeviceEventOutcome.Status.ERROR,
                    e.getMessage(),
                    null);
        }
    }

    /**
     * Processes a batch of offline events. Each event is handled independently — one failure
     * does not roll back accepted events in the same batch.
     */
    @Transactional
    public List<DeviceEventOutcome> processBulkSync(UUID deviceApiKeyId, DeviceBulkSyncRequest req) {
        List<DeviceEventOutcome> outcomes = new ArrayList<>();
        if (req.getEvents() == null || req.getEvents().isEmpty()) {
            return outcomes;
        }
        for (DeviceAttendanceEventRequest event : req.getEvents()) {
            outcomes.add(processEvent(deviceApiKeyId, event));
        }
        long accepted = outcomes.stream()
                .filter(o -> o.getStatus() == DeviceEventOutcome.Status.ACCEPTED).count();
        log.info("Bulk sync complete: device={} total={} accepted={}", deviceApiKeyId,
                req.getEvents().size(), accepted);
        return outcomes;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AttendanceRecordDTO dispatchEvent(DeviceEventType eventType, UUID employeeId, LocalDateTime timestamp) {
        if (eventType == DeviceEventType.CHECK_IN) {
            return attendanceService.processDeviceCheckIn(employeeId, timestamp);
        } else if (eventType == DeviceEventType.CHECK_OUT) {
            return attendanceService.processDeviceCheckOut(employeeId, timestamp);
        }
        throw new IllegalArgumentException("Unknown event type: " + eventType);
    }

    private void logSyncError(UUID deviceApiKeyId, DeviceAttendanceEventRequest req,
                              String reason, String details, String rawPayload) {
        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;

        DeviceApiKey device = deviceApiKeyRepository.findById(deviceApiKeyId).orElse(null);
        String errorReason = details != null ? details.substring(0, Math.min(details.length(), 499)) : reason;

        DeviceSyncError error = DeviceSyncError.builder()
                .tenant(tenant)
                .deviceApiKey(device)
                .deviceEmployeeId(req.getEmployeeIdentifier())
                .deviceIdentifier(req.getDeviceId())
                .eventType(req.getEventType() != null ? req.getEventType().name() : null)
                .eventTimestamp(req.getTimestamp() != null ? req.getTimestamp().atOffset(java.time.ZoneOffset.UTC) : null)
                .errorReason(errorReason)
                .rawPayload(rawPayload)
                .build();

        syncErrorRepository.save(error);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
