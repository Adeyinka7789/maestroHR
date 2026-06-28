package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/platform-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPlatformSettingsController {

    private final PlatformSettingsService platformSettings;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("Platform settings", platformSettings.getAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> save(@RequestBody Map<String, String> data) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        platformSettings.setMultiple(data, actor);
        return ResponseEntity.ok(ApiResponse.success("Settings saved", null));
    }
}
