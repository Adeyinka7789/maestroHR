package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.subscription.FeatureFlagOverride;
import com.admtechhub.maestrohr.subscription.PlatformFlag;
import com.admtechhub.maestrohr.subscription.PlatformFlagService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Layered feature-flag admin API: per-tenant/per-plan overrides and rollout percentage on top
 * of the {@code platform_flags} global kill switch managed by {@code FeatureFlagsAdminController}
 * (the {@code /htmx/admin/feature-flags} page). Consumed by the JS panel in
 * {@code admin-feature-flags.html}, same fetch/Bearer-token pattern as
 * {@code AdminPlatformSettingsController}.
 */
@RestController
@RequestMapping("/api/admin/feature-flags")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminFeatureFlagApiController {

    private final PlatformFlagService platformFlagService;

    @GetMapping("/{flagName}/overrides")
    public ResponseEntity<ApiResponse<List<FeatureFlagOverride>>> listOverrides(@PathVariable String flagName) {
        return ResponseEntity.ok(
                ApiResponse.success("Overrides retrieved", platformFlagService.listOverrides(flagName)));
    }

    @PostMapping("/{flagName}/overrides")
    public ResponseEntity<ApiResponse<FeatureFlagOverride>> createOverride(
            @PathVariable String flagName, @RequestBody OverrideRequest request) {
        if (request.getTargetType() == null || request.getTargetValue() == null || request.getTargetValue().isBlank()) {
            throw new IllegalArgumentException("targetType and targetValue are required");
        }
        FeatureFlagOverride override = platformFlagService.createOverride(
                flagName, request.getTargetType(), request.getTargetValue(),
                request.isEnabled(), request.getReason(), actorEmail());
        return ResponseEntity.ok(ApiResponse.success("Override saved", override));
    }

    @DeleteMapping("/{flagName}/overrides/{overrideId}")
    public ResponseEntity<ApiResponse<Void>> deleteOverride(
            @PathVariable String flagName, @PathVariable UUID overrideId) {
        platformFlagService.deleteOverride(overrideId);
        return ResponseEntity.ok(ApiResponse.success("Override removed", null));
    }

    @PatchMapping("/{flagName}/rollout")
    public ResponseEntity<ApiResponse<PlatformFlag>> setRollout(
            @PathVariable String flagName, @RequestBody RolloutRequest request) {
        PlatformFlag flag = platformFlagService.setRolloutPercentage(flagName, request.getPercentage(), actorEmail());
        return ResponseEntity.ok(ApiResponse.success("Rollout updated", flag));
    }

    private String actorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @Data
    public static class OverrideRequest {
        private FeatureFlagOverride.TargetType targetType;
        private String targetValue;
        private boolean enabled;
        private String reason;
    }

    @Data
    public static class RolloutRequest {
        private int percentage;
    }
}
