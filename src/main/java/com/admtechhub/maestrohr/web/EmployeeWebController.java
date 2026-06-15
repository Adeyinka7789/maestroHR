package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.auth.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeWebController {

    private final JwtService jwtService;

    // NOTE: the employees list (/employees + /htmx/employees) is owned by
    // EmployeesController, which renders it as a server-rendered HTMX fragment with
    // department/status filters. This controller handles only the create/view/edit
    // sub-routes, which still forward to their static pages.

    @GetMapping("/create")
    public String showCreateForm(
            HttpServletRequest request,
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        return htmx != null ? "forward:/employee-create.html" : "forward:/layout.html";
    }

    @GetMapping("/{id}")
    public String viewEmployee(
            @PathVariable UUID id,
            HttpServletRequest request,
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        // The static page reads the id from window.location.pathname client-side.
        // For HTMX swaps hx-push-url keeps the URL correct, so the page can still
        // read it. For direct access the full layout is served and the page reads it
        // from the URL on load.
        return htmx != null ? "forward:/employee-view.html" : "forward:/layout.html";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(
            @PathVariable UUID id,
            HttpServletRequest request,
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        if (!setTenant(request)) return "redirect:/login";
        TenantContext.clear();
        return htmx != null ? "forward:/employee-edit.html" : "forward:/layout.html";
    }

    // ── JWT / tenant helpers (unchanged) ─────────────────────────────────────

    private boolean setTenant(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) return false;
        try {
            if (!jwtService.isTokenValid(token)) return false;
            TenantContext.setCurrentTenant(jwtService.extractTenantId(token));
            return true;
        } catch (Exception e) {
            log.error("JWT validation error: {}", e.getMessage());
            return false;
        }
    }

    private String extractToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7);
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("maestrohr_token".equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }
}