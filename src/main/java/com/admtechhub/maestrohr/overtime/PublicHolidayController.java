package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Public-holiday calendar settings (server-rendered HTMX fragment). The tenant admin curates which
 * dates their business observes as holidays; the overtime calculator bills all hours worked on an
 * active holiday at the policy's holiday multiplier. Role-gated to HR / admin (no feature flag — a
 * settings page), mirroring the users-management page.
 */
@Controller
@RequiredArgsConstructor
public class PublicHolidayController {

    private final PublicHolidayService holidayService;
    private final FeatureAccessService featureAccessService;

    private static final String[] ROLES = {
            "ROLE_HR_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    @GetMapping("/htmx/holidays")
    public String holidays(@RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (htmx == null) {
            return "forward:/layout.html";
        }
        gate();
        featureAccessService.require(SubscriptionFeature.PUBLIC_HOLIDAYS);
        model.addAttribute("holidays", holidayService.list());
        return "holidays :: content";
    }

    @PostMapping("/htmx/holidays/add")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String add(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      @RequestParam String name, Model model) {
        holidayService.add(date, name);
        model.addAttribute("success", "Holiday added.");
        model.addAttribute("holidays", holidayService.list());
        return "holidays :: content";
    }

    @PostMapping("/htmx/holidays/{id}/toggle")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String toggle(@PathVariable UUID id, @RequestParam boolean active, Model model) {
        holidayService.setActive(id, active);
        model.addAttribute("success", active ? "Holiday activated." : "Holiday deactivated.");
        model.addAttribute("holidays", holidayService.list());
        return "holidays :: content";
    }

    @PostMapping("/htmx/holidays/{id}/delete")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public String delete(@PathVariable UUID id, Model model) {
        holidayService.delete(id);
        model.addAttribute("success", "Holiday removed.");
        model.addAttribute("holidays", holidayService.list());
        return "holidays :: content";
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleFailure(RuntimeException ex, Model model) {
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("holidays", holidayService.list());
        return "holidays :: content";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    /** Feature off / not entitled: locked state only — never load holiday data. */
    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Public Holidays");
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
    }
}
