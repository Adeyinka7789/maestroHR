package com.admtechhub.maestrohr.analytics;

import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Executive / CEO analytics dashboard (server-rendered HTMX fragment). Read-only lenses over
 * payroll / leave / overtime — Real Cost of Labor, departmental payroll spikes, and burnout risk.
 * Gated by {@link SubscriptionFeature#CUSTOM_REPORTING} with a manual role check on the page GET,
 * mirroring the other reporting pages; denials render as in-place HTTP-200 fragments.
 */
@Controller
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final FeatureAccessService featureAccessService;

    private static final String[] ROLES = {
            "ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    @GetMapping("/htmx/analytics")
    public String analytics(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        gate();
        model.addAttribute("view", analyticsService.build());
        return "analytics :: content";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Executive Analytics");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }

    private void gate() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = false;
        if (auth != null) {
            for (GrantedAuthority granted : auth.getAuthorities()) {
                for (String role : ROLES) {
                    if (role.equals(granted.getAuthority())) {
                        allowed = true;
                    }
                }
            }
        }
        if (!allowed) {
            throw new AccessDeniedException("You don't have access to this page.");
        }
        featureAccessService.require(SubscriptionFeature.CUSTOM_REPORTING);
    }
}
