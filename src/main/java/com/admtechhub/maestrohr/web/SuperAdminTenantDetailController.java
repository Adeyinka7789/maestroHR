package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.platform.AdminBillingWrites;
import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.platform.AuditTrailWrites;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Server-rendered HTMX fragment controller for the super-admin tenant detail page.
 *
 * <p>GET renders the full detail view; POST /suspend and POST /reactivate perform the action
 * (via the same privileged-JDBC path as the REST equivalents in {@code AdminManagementController})
 * and re-render the fragment so HTMX swaps in the updated state without a full navigation.
 *
 * <p>URL-level security ({@code /htmx/admin/**} → SUPER_ADMIN in SecurityConfig) is the primary
 * gate; {@code @PreAuthorize} is the method-level backstop per the project pattern.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminTenantDetailController {

    private final AdminStatsQueries adminStatsQueries;
    private final TenantUserWrites tenantUserWrites;
    private final AdminBillingWrites adminBillingWrites;
    private final AuditTrailWrites auditTrailWrites;

    @GetMapping("/htmx/admin/tenants/{id}")
    public String tenantDetail(
            @PathVariable UUID id,
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        return renderDetail(id, model);
    }

    @PostMapping("/htmx/admin/tenants/{id}/suspend")
    public String suspendTenant(
            @PathVariable UUID id,
            HttpServletRequest request,
            Model model) {

        if (tenantUserWrites.suspendTenant(id)) {
            auditTrailWrites.insert(id, actorEmail(), "TENANT_SUSPENDED", "tenant", id.toString(),
                    request.getRequestURI(), "POST", request.getRemoteAddr(), 200, null);
        }
        return renderDetail(id, model);
    }

    @PostMapping("/htmx/admin/tenants/{id}/reactivate")
    public String reactivateTenant(
            @PathVariable UUID id,
            HttpServletRequest request,
            Model model) {

        if (tenantUserWrites.reactivateTenant(id)) {
            auditTrailWrites.insert(id, actorEmail(), "TENANT_REACTIVATED", "tenant", id.toString(),
                    request.getRequestURI(), "POST", request.getRemoteAddr(), 200, null);
        }
        return renderDetail(id, model);
    }

    @PostMapping("/htmx/admin/tenants/{id}/change-plan")
    public String changePlan(
            @PathVariable UUID id,
            @RequestParam SubscriptionPlan plan,
            @RequestParam PaymentPeriod period,
            HttpServletRequest request,
            Model model) {

        if (adminBillingWrites.changePlan(id, plan, period)) {
            auditTrailWrites.insert(id, actorEmail(), "TENANT_PLAN_CHANGED", "tenant", id.toString(),
                    request.getRequestURI(), "POST", request.getRemoteAddr(), 200,
                    plan.name() + " / " + period.name());
            model.addAttribute("successMessage",
                    "Plan changed to " + plan.name() + " (" + period.name() + ").");
        } else {
            model.addAttribute("errorMessage", "Could not change plan — tenant has no subscription.");
        }
        return renderDetail(id, model);
    }

    @PostMapping("/htmx/admin/tenants/{id}/extend-trial")
    public String extendTrial(
            @PathVariable UUID id,
            @RequestParam int days,
            HttpServletRequest request,
            Model model) {

        if (days <= 0) {
            model.addAttribute("errorMessage", "Days to extend must be a positive number.");
        } else if (adminBillingWrites.extendTrial(id, days)) {
            auditTrailWrites.insert(id, actorEmail(), "TRIAL_EXTENDED", "tenant", id.toString(),
                    request.getRequestURI(), "POST", request.getRemoteAddr(), 200, "+" + days + " days");
            model.addAttribute("successMessage", "Trial extended by " + days + " days.");
        } else {
            model.addAttribute("errorMessage", "Could not extend trial — tenant is not on a trial.");
        }
        return renderDetail(id, model);
    }

    private String renderDetail(UUID id, Model model) {
        AdminStatsQueries.TenantDetailDTO detail = adminStatsQueries.findTenantDetail(id);
        model.addAttribute("detail", detail);
        return "admin-tenant-detail :: content";
    }

    private String actorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
