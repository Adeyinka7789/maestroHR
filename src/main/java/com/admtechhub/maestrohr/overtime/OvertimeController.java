package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.subscription.FeatureAccessException;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
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

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Overtime &amp; shift-allowance page (server-rendered HTMX fragment, Option 3). HR computes
 * overtime for a period from attendance, reviews it, and approves — each approval emits a payroll
 * adjustment the run consumes. Gated by {@link SubscriptionFeature#ATTENDANCE_TRACKING} (the source
 * data is attendance); access mirrors {@code AttendanceListController} — a manual role check plus a
 * feature check on the fragment routes, with denials rendered as in-place HTTP-200 fragments so
 * layout.js never sees a raw 403/402.
 */
@Controller
@RequiredArgsConstructor
public class OvertimeController {

    private final OvertimeService overtimeService;
    private final FeatureAccessService featureAccessService;

    private static final String[] ROLES = {
            "ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    /** Full page: app shell on a cold visit, the populated fragment under HTMX. */
    @GetMapping("/htmx/overtime")
    public String overtime(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "year", required = false) Integer year,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }
        gate();
        YearMonth period = resolvePeriod(month, year);
        model.addAttribute("view", overtimeService.buildPageView(period.getMonthValue(), period.getYear()));
        return "overtime :: content";
    }

    /** Recompute overtime for the period from attendance, then re-render. */
    @PostMapping("/htmx/overtime/compute")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String compute(@RequestParam int month, @RequestParam int year, Model model) {
        OvertimeService.ComputeResult result = overtimeService.computeForPeriod(month, year);
        model.addAttribute("success", String.format(
                "Computed overtime from attendance: %d employee(s) scanned, %d with overtime.",
                result.employeesScanned(), result.entriesWithOvertime()));
        model.addAttribute("view", overtimeService.buildPageView(month, year));
        return "overtime :: content";
    }

    /** Approve one draft entry — emits a payroll adjustment — then re-render. */
    @PostMapping("/htmx/overtime/{id}/approve")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String approve(@PathVariable UUID id, @RequestParam int month, @RequestParam int year, Model model) {
        overtimeService.approve(id, currentUserEmail());
        model.addAttribute("success", "Overtime approved and added to the next payroll run.");
        model.addAttribute("view", overtimeService.buildPageView(month, year));
        return "overtime :: content";
    }

    /** Reject one entry, then re-render. */
    @PostMapping("/htmx/overtime/{id}/reject")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String reject(@PathVariable UUID id, @RequestParam int month, @RequestParam int year, Model model) {
        overtimeService.reject(id);
        model.addAttribute("success", "Overtime entry rejected.");
        model.addAttribute("view", overtimeService.buildPageView(month, year));
        return "overtime :: content";
    }

    /** Save the tenant's overtime rate card, then re-render the same period. */
    @PostMapping("/htmx/overtime/policy")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String savePolicy(
            @RequestParam BigDecimal standardDailyHours,
            @RequestParam int standardMonthlyHours,
            @RequestParam BigDecimal weekdayMultiplier,
            @RequestParam BigDecimal weekendMultiplier,
            @RequestParam int month,
            @RequestParam int year,
            Model model) {
        overtimeService.updatePolicy(standardDailyHours, standardMonthlyHours, weekdayMultiplier, weekendMultiplier);
        model.addAttribute("success", "Overtime policy saved.");
        model.addAttribute("view", overtimeService.buildPageView(month, year));
        return "overtime :: content";
    }

    // ── failure rendering (in-place HTTP-200 fragments) ────────────────────────────

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleFailure(RuntimeException ex,
                                @RequestParam(required = false) Integer month,
                                @RequestParam(required = false) Integer year,
                                Model model) {
        YearMonth period = resolvePeriod(month, year);
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("view", overtimeService.buildPageView(period.getMonthValue(), period.getYear()));
        return "overtime :: content";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    @ExceptionHandler(FeatureAccessException.class)
    public String handleFeatureLocked(FeatureAccessException ex, Model model) {
        model.addAttribute("lockTitle", "Overtime");
        model.addAttribute("formError", ex.getMessage());
        return "fragments/feature-locked :: locked";
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private void gate() {
        if (!hasAnyRole(ROLES)) {
            throw new AccessDeniedException("You don't have access to this page.");
        }
        featureAccessService.require(SubscriptionFeature.ATTENDANCE_TRACKING);
    }

    /** Default to the current month when params are absent; clamps a bad month to a valid one. */
    private YearMonth resolvePeriod(Integer month, Integer year) {
        YearMonth now = YearMonth.now();
        int m = (month == null || month < 1 || month > 12) ? now.getMonthValue() : month;
        int y = (year == null || year < 2000 || year > 2100) ? now.getYear() : year;
        return YearMonth.of(y, m);
    }

    private boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            for (String role : roles) {
                if (role.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
