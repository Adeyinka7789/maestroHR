package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Server-rendered HTMX fragment controller for the super-admin analytics console (Feature 3 — the
 * same Option-3 pattern as {@link SuperAdminDashboardController} / {@link AdminBillingController}).
 *
 * <p>Read-only: GET renders the platform-wide analytics charts. A full-page visit returns the app
 * shell ({@code layout.html}), which then re-requests this route with the {@code HX-Request} header.
 *
 * <p>Authorization is enforced at the URL layer ({@code /htmx/admin/**} → SUPER_ADMIN in
 * SecurityConfig); {@code @PreAuthorize} is the method-level backstop per the project pattern.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAnalyticsController {

    private final AdminAnalyticsService adminAnalyticsService;

    @GetMapping("/htmx/admin/analytics")
    public String analytics(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        model.addAttribute("view", adminAnalyticsService.build());
        return "admin-analytics :: content";
    }
}
