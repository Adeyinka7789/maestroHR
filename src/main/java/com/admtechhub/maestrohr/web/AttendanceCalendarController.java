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
 * Attendance Monthly Calendar route (Step B, Option 3 — server-rendered fragment),
 * companion to {@link AttendanceListController}. The attendance page surfaces a
 * Today / Calendar tab toggle (rendered by the {@code attendance :: tabs} fragment);
 * the Calendar tab swaps {@code attendance :: calendar} into {@code #page-content}.
 *
 * A single GET drives every interaction — the tab toggle, the employee picker, and the
 * prev/next month navigation — each re-rendering the whole calendar fragment (picker +
 * month nav + grid + summary) with the requested employee and month. A non-HTMX visit
 * returns the static layout shell and layout.js re-requests this route with HX-Request.
 *
 * SCOPE (Step B): read-only. The write workflows — Mark Attendance and Self check-in/out
 * (Steps C/D) — are deferred to later, separately-reviewed steps because the write paths
 * feed payroll deductions.
 *
 * Named {@code AttendanceCalendarController} (not on the REST {@code attendanceController}
 * bean name) for the same reason as {@link AttendanceListController}.
 *
 * ACCESS: admin/manager-only, same role list as {@link AttendanceListController#mark}
 * ({@code hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')} — a
 * {@code SYSTEM_ADMIN} authority is checked explicitly too, since that expression's
 * {@code @PreAuthorize} normally gets it for free via the configured {@code RoleHierarchy}
 * (SUPER_ADMIN &gt; SYSTEM_ADMIN &gt; HR_ADMIN) but this manual check does a plain string
 * match with no hierarchy expansion). There is no EMPLOYEE self-access branch: this route is
 * the employee-*picker* calendar embedded in the HR-admin roster page ({@code <select
 * name="empId">} lets the caller view ANY employee's calendar) — genuine self-service
 * attendance viewing is a separate, simpler page at {@code /htmx/attendance/me}
 * ({@link AttendanceSelfController}), which always resolves the employee from the session and
 * never accepts an {@code empId} param. So the fix here is a role gate only, not an
 * ownership/IDOR check — nobody outside the four admin/manager roles can reach the
 * {@code empId}-driven query at all, regardless of whose id they pass.
 */
@Controller
@RequiredArgsConstructor
public class AttendanceCalendarController {

    private final AttendanceCalendarService attendanceCalendarService;

    /** Full calendar fragment: app shell on a cold visit, the populated grid under HTMX. */
    @GetMapping("/htmx/attendance/calendar")
    public String calendar(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            @RequestParam(value = "empId", required = false) String empId,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        if (!hasAnyRole("ROLE_HR_ADMIN", "ROLE_FINANCE_OFFICER", "ROLE_DEPT_MANAGER",
                "ROLE_SUPER_ADMIN", "ROLE_SYSTEM_ADMIN")) {
            throw new AccessDeniedException("You don't have access to this page.");
        }

        model.addAttribute("view",
                attendanceCalendarService.build(parseEmployeeId(empId), year, month));
        return "attendance :: calendar";
    }

    /**
     * Renders access-denial as a data-free fragment (HTTP 200, so HTMX still performs the
     * swap) instead of letting a raw 403 reach layout.js's {@code htmx:responseError} handler,
     * which would clear localStorage and bounce the user to login.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }

    /**
     * Parses the employee id from the picker. Absent, blank, or unparseable values fall
     * back to null — the service then renders the "select an employee" prompt rather than
     * a 400, so a malformed param degrades gracefully (mirrors the Step-A date parsing).
     */
    private UUID parseEmployeeId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when the authenticated caller holds any of the given ROLE_* authorities. */
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
