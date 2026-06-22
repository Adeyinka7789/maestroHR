package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AuditTrailWrites;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Server-rendered HTMX fragment controller for the super-admin billing console (Feature 2 — the
 * same Option-3 pattern as {@link SuperAdminDashboardController} / {@link SuperAdminTenantDetailController}).
 *
 * <p>GET renders the platform-wide billing overview; POST /tenants/{id}/reactivate clears a tenant's
 * past-due/cancelled state (the "Mark Active" action on the dunning list) and re-renders the billing
 * fragment in place so the past-due table updates without navigating away.
 *
 * <p>Authorization is enforced at the URL layer ({@code /htmx/admin/**} → SUPER_ADMIN in
 * SecurityConfig); {@code @PreAuthorize} is the method-level backstop per the project pattern.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminBillingController {

    private final AdminBillingService adminBillingService;
    private final TenantUserWrites tenantUserWrites;
    private final AuditTrailWrites auditTrailWrites;

    @GetMapping("/htmx/admin/billing")
    public String billing(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        model.addAttribute("view", adminBillingService.buildOverview());
        return "admin-billing :: content";
    }

    @PostMapping("/htmx/admin/billing/tenants/{id}/reactivate")
    public String reactivate(
            @PathVariable UUID id,
            HttpServletRequest request,
            Model model) {

        if (tenantUserWrites.reactivateTenant(id)) {
            auditTrailWrites.insert(id, actorEmail(), "TENANT_REACTIVATED", "tenant", id.toString(),
                    request.getRequestURI(), "POST", request.getRemoteAddr(), 200, null);
        }
        model.addAttribute("view", adminBillingService.buildOverview());
        return "admin-billing :: content";
    }

    private String actorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
