package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Super-admin platform dashboard route (Option 3 — server-rendered HTMX fragment, the same
 * pattern as {@link DashboardController}). Replaces the former static-forward in
 * {@code PageController}.
 *
 * <p>Authorization is enforced at the URL layer: {@code SecurityConfig} gates {@code /htmx/admin}
 * to {@code SUPER_ADMIN}. An HTMX request renders the populated fragment; a full-page visit returns
 * the app shell, which then re-requests this route with the {@code HX-Request} header.
 */
@Controller
@RequiredArgsConstructor
public class SuperAdminDashboardController {

    private final SuperAdminDashboardService superAdminDashboardService;

    @GetMapping("/htmx/admin")
    public String adminDashboard(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        model.addAttribute("view", superAdminDashboardService.build());
        return "admin-dashboard :: content";
    }
}
