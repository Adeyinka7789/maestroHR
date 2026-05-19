package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Dashboard page route.
 * All data is fetched client-side via /api/dashboard/* REST endpoints.
 * Server-side model attributes removed — they were discarded by the redirect anyway.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    @GetMapping("/dashboard")
    public String dashboard(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/dashboard.html" : "forward:/layout.html";
    }

    @GetMapping("/")
    public String home() {
        return "home";
    }
}