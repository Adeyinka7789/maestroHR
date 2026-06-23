package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/features")
@RequiredArgsConstructor
public class ActiveFeaturesController {

    private final PlatformFlagService platformFlagService;

    /**
     * Returns the names of all SubscriptionFeature flags that are currently enabled
     * platform-wide. Absent rows default to enabled (backward-compatible). Used by
     * layout.js to hide nav items whose feature is disabled.
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<String>>> activeFeatures() {
        Map<String, Boolean> flagMap = platformFlagService.listAll().stream()
                .collect(Collectors.toMap(PlatformFlag::getName, PlatformFlag::isEnabled));
        List<String> active = Arrays.stream(SubscriptionFeature.values())
                .map(SubscriptionFeature::name)
                .filter(name -> flagMap.getOrDefault(name, true))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("ok", active));
    }
}
