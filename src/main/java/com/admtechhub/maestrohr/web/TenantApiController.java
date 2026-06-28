package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantApiController {

    private final PlatformSettingsService platformSettings;

    @GetMapping("/billing-history")
    public ResponseEntity<?> getBillingHistory() {
        return ResponseEntity.ok(Map.of("success", true, "data", Collections.emptyList()));
    }

    @GetMapping("/support-contacts")
    public ResponseEntity<?> getSupportContacts() {
        return ResponseEntity.ok(Map.of(
                "supportEmail", platformSettings.getOrDefault("support_email", ""),
                "supportWhatsapp", platformSettings.getOrDefault("support_whatsapp", "")));
    }
}