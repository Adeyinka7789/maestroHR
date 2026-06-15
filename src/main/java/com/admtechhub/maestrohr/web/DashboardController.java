package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Dashboard page route.
 *
 * PILOT (Option 3 — server-rendered fragment): the HTMX request renders
 * templates/dashboard.html with data already in the markup (no /api/dashboard/*
 * double-fetch, no loading flash). A non-HTMX visit returns the static layout
 * shell; layout.js then re-requests this route with the HX-Request header and
 * swaps the fragment into #page-content — same mechanism as every other page.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping({"/dashboard", "/htmx/dashboard"})
    public String dashboard(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Authentication authentication,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        model.addAttribute("view", dashboardService.buildOverview());
        model.addAttribute("superAdmin", isSuperAdmin(authentication));
        return "dashboard :: content";
    }

    @GetMapping("/")
    public String home() {
        return "home";
    }

    private boolean isSuperAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
