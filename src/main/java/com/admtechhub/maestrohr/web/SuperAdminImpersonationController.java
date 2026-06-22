package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Server-rendered HTMX fragment for the super-admin impersonation picker (Feature 4 — same
 * Option-3 pattern as {@link SuperAdminDashboardController}). Lists every non-super-admin user
 * across all tenants; the per-row "Impersonate" action is driven client-side from the rendered
 * fragment (POST /api/admin/impersonate/{userId}, then a token swap + redirect).
 *
 * <p>URL-level security gates {@code /htmx/admin/**} to SUPER_ADMIN in {@code SecurityConfig};
 * {@code @PreAuthorize} is the method-level backstop per the project pattern.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminImpersonationController {

    private final AdminStatsQueries adminStatsQueries;

    @GetMapping("/htmx/admin/impersonation")
    public String impersonation(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        model.addAttribute("users", adminStatsQueries.findAllUsersForImpersonation());
        return "admin-impersonation :: content";
    }
}
