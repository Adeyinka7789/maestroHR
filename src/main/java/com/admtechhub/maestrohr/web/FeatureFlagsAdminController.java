package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.subscription.PlatformFlagService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Super-admin global feature-flag page ({@code /htmx/admin/feature-flags}) — same Option-3
 * fragment pattern as the other {@code /htmx/admin/**} pages. GET renders every
 * {@link com.admtechhub.maestrohr.subscription.PlatformFlag}; POST enable/disable flips a
 * flag and re-renders the table.
 *
 * <p>A platform flag is a kill switch: turning a feature OFF here removes it for every tenant,
 * regardless of plan (the global check is evaluated before the tenant/plan check in
 * {@code FeatureFlagService}). Authorization is enforced at the URL layer
 * ({@code /htmx/admin/**} → SUPER_ADMIN in SecurityConfig); {@code @PreAuthorize} is the
 * method-level backstop.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class FeatureFlagsAdminController {

    private final PlatformFlagService platformFlagService;
    private final TenantRepository tenantRepository;

    @GetMapping("/htmx/admin/feature-flags")
    public String featureFlags(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        return render(model);
    }

    @PostMapping("/htmx/admin/feature-flags/{name}/enable")
    public String enable(@PathVariable String name, Model model) {
        platformFlagService.enable(name, actorEmail());
        model.addAttribute("successMessage", name + " enabled platform-wide.");
        return render(model);
    }

    @PostMapping("/htmx/admin/feature-flags/{name}/disable")
    public String disable(@PathVariable String name, Model model) {
        platformFlagService.disable(name, actorEmail());
        model.addAttribute("successMessage", name + " disabled platform-wide.");
        return render(model);
    }

    private String render(Model model) {
        model.addAttribute("flags", platformFlagService.listAll());
        model.addAttribute("tenants", tenantRepository.findAllByOrderByCreatedAtDesc());
        model.addAttribute("plans", SubscriptionPlan.values());
        return "admin-feature-flags :: content";
    }

    private String actorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
