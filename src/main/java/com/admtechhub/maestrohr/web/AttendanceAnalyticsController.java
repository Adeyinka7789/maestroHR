package com.admtechhub.maestrohr.web;

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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Attendance Analytics route (Option 3 — server-rendered fragment), the third tab on the
 * attendance page alongside {@link AttendanceListController} (Today) and
 * {@link AttendanceCalendarController} (Calendar). Where the Calendar answers "how was ONE
 * employee this month", this answers "how was the WHOLE team over a period" — company-wide
 * summary cards, a per-employee summary table, a per-department breakdown, and a day-by-day
 * attendance trend.
 *
 * A single GET drives every interaction — the tab toggle, the department filter, and the
 * prev/next month navigation — each re-rendering the whole analytics fragment. A non-HTMX
 * visit returns the static layout shell and layout.js re-requests this route with HX-Request
 * (mirrors the sibling controllers).
 *
 * Named {@code AttendanceAnalyticsController} (not on the REST {@code attendanceController}
 * bean name) for the same reason as {@link AttendanceListController}.
 *
 * ACCESS: admin/manager-only, the same role list as the sibling read routes. The manual check
 * (rather than {@code @PreAuthorize}) keeps a denial a plain {@link AccessDeniedException}
 * rendered as an in-place fragment (HTTP 200), so it never trips layout.js's responseError
 * logout redirect. {@code SYSTEM_ADMIN} is listed explicitly because the manual check does a
 * plain string match with no {@code RoleHierarchy} expansion. Read-only: no write path, so no
 * {@code @RequiresFeature} gate (consistent with the Today/Calendar reads).
 */
@Controller
@RequiredArgsConstructor
public class AttendanceAnalyticsController {

    private static final String[] READ_ROLES = {
            "ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_DEPT_MANAGER",
            "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN"
    };

    private final AttendanceAnalyticsService attendanceAnalyticsService;

    /**
     * Full analytics fragment: app shell on a cold visit, the populated dashboard under HTMX.
     *
     * The period is chosen by {@code period} (day / week / month / custom, default month). For
     * the presets, {@code date} is an anchor date inside the wanted period (prev/next shift it);
     * for {@code custom}, {@code from}/{@code to} are the range (which also drives the export).
     * All params are optional and leniently parsed by the service (bad input → current month).
     */
    @GetMapping("/htmx/attendance/analytics")
    public String analytics(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "deptId", required = false) String deptId,
            Model model) {

        if (htmx == null) {
            return "forward:/layout.html";
        }

        if (!hasAnyRole(READ_ROLES)) {
            throw new AccessDeniedException("You don't have access to this page.");
        }

        model.addAttribute("view",
                attendanceAnalyticsService.build(period, date, from, to, parseDepartmentId(deptId)));
        return "attendance-analytics :: analytics";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    /** Absent/blank/unparseable department id → null = All departments (mirrors the calendar's empId parse). */
    private UUID parseDepartmentId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
}
