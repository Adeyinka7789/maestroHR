package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Device-facing attendance sync endpoints. Requests reach this controller through the
 * device {@link com.admtechhub.maestrohr.config.SecurityConfig} chain (@Order(1)):
 * they are authenticated via {@code X-Device-Api-Key} and carry {@code ROLE_DEVICE}.
 * No JWT, no user session.
 *
 * <p>The device API key UUID is extracted from the synthetic principal name set by
 * {@link com.admtechhub.maestrohr.auth.DeviceAuthFilter} ("device:{uuid}") so the
 * service can resolve the correct enrollment scope without the caller needing to
 * supply the device ID in the body.
 */
@RestController
@RequestMapping("/api/v1/device")
@RequiredArgsConstructor
@Slf4j
@RequiresFeature(SubscriptionFeature.HARDWARE_SYNC)
public class DeviceAttendanceController {

    private final DeviceAttendanceService deviceAttendanceService;

    @PostMapping("/attendance/event")
    @PreAuthorize("hasRole('DEVICE')")
    public ResponseEntity<ApiResponse<DeviceEventOutcome>> pushEvent(
            @RequestBody DeviceAttendanceEventRequest req,
            @AuthenticationPrincipal UserDetails principal) {

        UUID deviceApiKeyId = extractDeviceId(principal);
        DeviceEventOutcome outcome = deviceAttendanceService.processEvent(deviceApiKeyId, req);
        return ResponseEntity.ok(ApiResponse.success("Event processed", outcome));
    }

    @PostMapping("/attendance/bulk-sync")
    @PreAuthorize("hasRole('DEVICE')")
    public ResponseEntity<ApiResponse<List<DeviceEventOutcome>>> bulkSync(
            @RequestBody DeviceBulkSyncRequest req,
            @AuthenticationPrincipal UserDetails principal) {

        UUID deviceApiKeyId = extractDeviceId(principal);
        List<DeviceEventOutcome> outcomes = deviceAttendanceService.processBulkSync(deviceApiKeyId, req);
        long accepted = outcomes.stream()
                .filter(o -> o.getStatus() == DeviceEventOutcome.Status.ACCEPTED).count();
        return ResponseEntity.ok(ApiResponse.success(
                "Bulk sync complete: " + accepted + "/" + outcomes.size() + " accepted", outcomes));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    /** Extracts the device UUID from the synthetic principal name ("device:{uuid}"). */
    private static UUID extractDeviceId(UserDetails principal) {
        String name = principal.getUsername();
        try {
            return UUID.fromString(name.startsWith("device:") ? name.substring(7) : name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed device principal: " + name);
        }
    }
}
