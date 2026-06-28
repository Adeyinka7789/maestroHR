package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.PlatformSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Server-rendered HTMX fragment for the super-admin platform settings page.
 * URL-level security ({@code /htmx/admin/**} → SUPER_ADMIN in SecurityConfig) is the primary gate;
 * {@code @PreAuthorize} is the method-level backstop.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminPlatformSettingsController {

    private final PlatformSettingsService platformSettings;

    @GetMapping("/htmx/admin/platform-settings")
    public String page(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        Map<String, String> settings = platformSettings.getAll();
        model.addAttribute("settings", settings);
        long minWageKobo = Long.parseLong(settings.getOrDefault("min_wage_kobo", "7000000"));
        model.addAttribute("minWageNaira", minWageKobo / 100);
        return "admin-platform-settings :: content";
    }
}
