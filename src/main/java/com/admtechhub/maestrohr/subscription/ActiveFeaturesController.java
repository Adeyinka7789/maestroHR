package com.admtechhub.maestrohr.subscription;
import com.admtechhub.maestrohr.flags.*;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/features")
@RequiredArgsConstructor
public class ActiveFeaturesController {

    private final PlatformFlagService platformFlagService;

    /**
     * Returns the names of all SubscriptionFeature flags that are currently enabled
     * platform-wide. Resolved through the same {@link PlatformFlagService#isEnabled(String)}
     * path used everywhere else, so nav visibility and gate enforcement share one policy — a
     * flag with no {@code platform_flags} row is treated as disabled (fail-closed), not shown.
     * ({@link PlatformFlagSeeder} + the seed migration guarantee every known flag has a row.)
     * Used by layout.js to hide nav items whose feature is disabled.
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<String>>> activeFeatures() {
        List<String> active = Arrays.stream(SubscriptionFeature.values())
                .map(SubscriptionFeature::name)
                .filter(platformFlagService::isEnabled)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("ok", active));
    }
}
