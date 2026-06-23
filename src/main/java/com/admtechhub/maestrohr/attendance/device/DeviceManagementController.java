package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HR/Admin management of device API keys and employee enrollments.
 * JWT-authenticated, HR_ADMIN or SUPER_ADMIN only.
 */
@RestController
@RequestMapping("/api/attendance/devices")
@RequiredArgsConstructor
@RequiresFeature(SubscriptionFeature.HARDWARE_SYNC)
public class DeviceManagementController {

    private final DeviceApiKeyService deviceApiKeyService;

    // ── Device API key management ─────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceKeyCreatedDTO>> createKey(
            @RequestBody CreateDeviceApiKeyRequest req) {
        DeviceKeyCreatedDTO created = deviceApiKeyService.createKey(req);
        return ResponseEntity.ok(ApiResponse.success(
                "Device API key created. Save the raw key now — it will not be shown again.", created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<DeviceApiKeyDTO>>> listKeys() {
        return ResponseEntity.ok(ApiResponse.success("Devices retrieved", deviceApiKeyService.listKeys()));
    }

    @PatchMapping("/{keyId}/revoke")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> revokeKey(@PathVariable UUID keyId) {
        deviceApiKeyService.revokeKey(keyId);
        return ResponseEntity.ok(ApiResponse.success("Device key revoked", null));
    }

    @DeleteMapping("/{keyId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteKey(@PathVariable UUID keyId) {
        deviceApiKeyService.deleteKey(keyId);
        return ResponseEntity.ok(ApiResponse.success("Device key deleted", null));
    }

    // ── Enrollment management ─────────────────────────────────────────────────

    @PostMapping("/enrollments")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceEnrollmentDTO>> createEnrollment(
            @RequestBody CreateEnrollmentRequest req) {
        DeviceEnrollmentDTO dto = deviceApiKeyService.createEnrollment(req);
        return ResponseEntity.ok(ApiResponse.success("Employee enrolled on device", dto));
    }

    @GetMapping("/{keyId}/enrollments")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<DeviceEnrollmentDTO>>> listEnrollments(
            @PathVariable UUID keyId) {
        return ResponseEntity.ok(ApiResponse.success("Enrollments retrieved",
                deviceApiKeyService.listEnrollments(keyId)));
    }

    @DeleteMapping("/enrollments/{enrollmentId}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteEnrollment(@PathVariable UUID enrollmentId) {
        deviceApiKeyService.deleteEnrollment(enrollmentId);
        return ResponseEntity.ok(ApiResponse.success("Enrollment removed", null));
    }

    // ── Sync error management ─────────────────────────────────────────────────

    @GetMapping("/sync-errors")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<DeviceSyncErrorDTO>>> listErrors(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success("Sync errors retrieved",
                deviceApiKeyService.listUnresolvedErrors(limit)));
    }

    @PatchMapping("/sync-errors/{errorId}/resolve")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resolveError(@PathVariable UUID errorId) {
        deviceApiKeyService.resolveError(errorId);
        return ResponseEntity.ok(ApiResponse.success("Error marked resolved", null));
    }

    @GetMapping("/sync-errors/count")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Long>> unresolvedErrorCount() {
        return ResponseEntity.ok(ApiResponse.success("Unresolved error count",
                deviceApiKeyService.countUnresolvedErrors()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }
}
