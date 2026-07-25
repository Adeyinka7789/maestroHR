package com.admtechhub.maestrohr.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@Controller
public class PageController {

    // Core pages
    // NOTE: /htmx/dashboard is owned by DashboardController (server-rendered fragment pilot).
    // NOTE: /htmx/employees (+ /htmx/employees/table) is owned by EmployeesController
    //       (server-rendered fragment with search + department/status filters).
    // NOTE: /htmx/departments (+ /htmx/departments/table) is owned by DepartmentsController
    //       (server-rendered fragment with name search). static/departments.html remains
    //       on disk as the legacy fallback until the new fragment is browser-verified.
    // NOTE: /htmx/pay-grades (+ /htmx/pay-grades/table) is owned by PayGradesController
    //       (server-rendered card grid with name search). static/pay-grades.html remains
    //       on disk as the legacy fallback until the new fragment is browser-verified.
    // NOTE: /htmx/leave (+ /htmx/leave/table) is owned by LeaveListController
    //       (server-rendered approval-queue + history table with a status filter and
    //       search). static/leave.html remains on disk as the legacy fallback until the
    //       new fragment is browser-verified.
    // NOTE: /htmx/attendance (+ /htmx/attendance/table) is owned by AttendanceListController
    //       (server-rendered daily roster with a date picker, status filter, and search).
    //       static/attendance.html remains on disk as the legacy fallback until the new
    //       fragment is browser-verified.
    // NOTE: /htmx/payroll (+ /htmx/payroll/table) is owned by PayrollListController and
    //       /htmx/payroll/{id} by PayrollDetailController (server-rendered run history /
    //       approval queue + per-run detail, with a status filter and search).
    //       static/payroll.html and static/payroll-detail.html remain on disk as the
    //       legacy fallbacks (the latter still served via /htmx/payroll-detail below)
    //       until the new fragments are browser-verified.
    // NOTE: /htmx/reports is owned by ReportsWebController (server-rendered fragment,
    //       Option 3 pattern). static/reports.html remains on disk as legacy fallback.

    @GetMapping("/htmx/recruitment")
    public String recruitment(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/recruitment.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/payroll-adjustments")
    public String payrollAdjustments(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/payroll-adjustments.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/performance-reviews")
    public String performanceReviews(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/performance-reviews.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/training-management")
    public String trainingManagement(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/training-management.html" : "forward:/layout.html";
    }

    // NOTE: /htmx/exit-management is owned by ExitManagementController (server-rendered fragment).

    @GetMapping("/htmx/audit")
    public String audit(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/audit.html" : "forward:/layout.html";
    }

    /**
     * Restricted to SYSTEM_ADMIN (tenant owner), HR_ADMIN, and SUPER_ADMIN — same gate as
     * {@link AttendancePolicyController} / {@link LoanPolicyController} / {@link ShiftController}.
     * Only the HTMX fragment fetch (which actually forwards to the billing/plan content in
     * settings.html) is gated; the cold, non-HTMX visit still returns the generic app shell like
     * every other route, since that response carries no tenant-specific content.
     *
     * The role check is manual (not {@code @PreAuthorize}) so the denial is a plain
     * {@link AccessDeniedException} caught by {@link #handleAccessDenied} below and rendered as
     * an in-place HTML fragment (HTTP 200) — a raw 403 here would trip layout.js's
     * {@code htmx:responseError} handler, which clears localStorage and bounces to login.
     */
    @GetMapping("/htmx/settings")
    public String settings(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        if (htmxRequest == null) {
            return "forward:/layout.html";
        }
        if (!hasAnyRole("ROLE_SYSTEM_ADMIN", "ROLE_HR_ADMIN", "ROLE_SUPER_ADMIN")) {
            throw new AccessDeniedException("You don't have access to this page.");
        }
        return "forward:/settings.html";
    }

    // NOTE: /htmx/admin is owned by SuperAdminDashboardController (server-rendered platform
    //       dashboard fragment, Option 3 pattern). static/admin.html remains on disk as the
    //       legacy tenant/user-management console until that view is rebuilt.

    // /htmx/admin/pricing serves the SUPER_ADMIN price editor (pricing.html), not the
    // customer-facing plan cards (plans.html, served by /htmx/plans).
    @GetMapping("/htmx/admin/pricing")
    public String adminPricing(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/pricing.html" : "forward:/layout.html";
    }

    // /htmx/admin/discounts serves the SUPER_ADMIN discount manager (discounts.html).
    @GetMapping("/htmx/admin/discounts")
    public String adminDiscounts(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/discounts.html" : "forward:/layout.html";
    }

    // Employee CRUD
    @GetMapping("/htmx/employee-create")
    public String employeeCreate(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-create.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/employee-edit")
    public String employeeEdit(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-edit.html" : "forward:/layout.html";
    }

    // /htmx/employee-view is now owned by EmployeesController (server-rendered
    // employee-detail fragment), replacing the legacy forward to employee-view.html.

    @GetMapping("/htmx/employee-dashboard")
    public String employeeDashboard(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/employee-dashboard.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/payroll-detail")
    public String payrollDetail(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/payroll-detail.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/plans")
    public String plans(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/plans.html" : "forward:/layout.html";
    }

    @GetMapping("/htmx/checkout")
    public String htmxCheckout(@RequestHeader(value = "HX-Request", required = false) String htmxRequest) {
        return htmxRequest != null ? "forward:/checkout.html" : "forward:/layout.html";
    }

    // NOTE: /htmx/subscribers (+ /htmx/subscribers/table) is owned by SubscribersListController
    //       (server-rendered fragment, Option 3 pattern, with company/plan search + status/plan
    //       filters). static/subscribers.html remains on disk as the legacy fallback until the
    //       new fragment is browser-verified.

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

    /**
     * Renders access-denial as an in-place HTML fragment (HTTP 200, so HTMX still performs the
     * swap — it skips swaps on 4xx/5xx by default) instead of letting a raw 403 reach
     * layout.js's {@code htmx:responseError} handler, which would clear localStorage and bounce
     * the user to login. Mirrors the write-failure handlers in {@code LeaveListController} /
     * {@code ShiftController} / {@code LoanPolicyController}, applied here to a read-access denial.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        model.addAttribute("errorMessage", ex.getMessage());
        return "access-denied :: content";
    }
}