package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Employee self-service "My Payslips" route (Option 3 — server-rendered fragment),
 * the read-only payslip companion to {@link AttendanceSelfController}. This is the
 * EMPLOYEE-facing surface: a clean list of the runs the authenticated employee has a
 * payslip for, each with a download link to the existing {@code /reports/payslip} PDF.
 *
 * Visible to any authenticated user (EMPLOYEE / HR_ADMIN / FINANCE_OFFICER /
 * SUPER_ADMIN) — {@code /htmx/**} is plain {@code .authenticated()} in SecurityConfig.
 *
 * The employee is always resolved from the session (never from a request param), so a
 * user can only ever see their own payslips. Admin/owner accounts with no Employee
 * record get a friendly no-profile card rather than a crash — same idiom as
 * {@link AttendanceSelfController}.
 *
 * Named {@code PayslipSelfController} (not on a payroll bean name) for the same reason
 * as the other web controllers — to avoid colliding with the REST payroll beans.
 */
@Controller
@RequiredArgsConstructor
public class PayslipSelfController {

    /**
     * Shown when the authenticated user has no Employee record (admin/owner accounts
     * that aren't employees). Payslips inherently belong to an employee, so those
     * accounts get this friendly message rather than an empty/erroring page.
     */
    private static final String NO_PROFILE_MESSAGE =
            "This page is for employees with payroll records. "
            + "Your account doesn't have an associated employee profile.";

    private final PayslipSelfService payslipSelfService;
    private final EmployeeService employeeService;

    /** Full page: app shell on a cold visit, the populated list under HTMX. */
    @GetMapping("/htmx/payslips")
    public String payslips(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        EmployeeDetailsDTO employee = currentEmployeeOrNull();
        if (employee == null) {
            model.addAttribute("noProfile", NO_PROFILE_MESSAGE);
            return "payslips :: content";
        }

        model.addAttribute("view", payslipSelfService.build(employee.getId(), employee.getFullName()));
        return "payslips :: content";
    }

    /**
     * Resolve the authenticated user's own Employee record, returning null instead of
     * throwing when the user has no Employee record (admin/owner accounts). Used by the
     * GET render so a missing profile shows a friendly message rather than propagating.
     */
    private EmployeeDetailsDTO currentEmployeeOrNull() {
        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            return employeeService.findByEmail(email);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
