package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Employee self check-in/out route (Step D, Option 3 — server-rendered fragment),
 * companion to the HR-facing {@link AttendanceListController} / {@link AttendanceCalendarController}.
 * This is the employee-facing surface: a single card showing today's status with
 * Check In / Check Out buttons and an optional notes field. A non-HTMX visit returns
 * the static layout shell and layout.js re-requests this route with the HX-Request header.
 *
 * SCOPE (Step D): the lower-stakes write — a single employee acting on their own record,
 * unlike Mark Attendance (any employee, any date, deferred). The employee is always
 * resolved from the authenticated session (never trusted from a request param), so a user
 * can only ever check themselves in/out.
 *
 * The guards and notes wiring live in {@link AttendanceService#checkIn}/{@code checkOut}
 * (shared with the REST endpoint). Double-action races throw {@link IllegalStateException}
 * — caught by {@link #handleActionFailure} and rendered as an in-place banner (HTTP 200 so
 * HTMX still swaps), mirroring {@link LeaveListController}.
 *
 * Named {@code AttendanceSelfController} (not on the REST {@code attendanceController} bean
 * name) for the same reason as the other web controllers.
 */
@Controller
@RequiredArgsConstructor
public class AttendanceSelfController {

    private final AttendanceSelfService attendanceSelfService;
    private final AttendanceService attendanceService;
    private final EmployeeService employeeService;

    /** Full page: app shell on a cold visit, the populated self card under HTMX. */
    @GetMapping("/htmx/attendance/me")
    public String me(
            @RequestHeader(value = "HX-Request", required = false) String htmx,
            Model model) {

        if (htmx == null) {
            // Full-page navigation: return the app shell; the fragment is fetched next.
            return "forward:/layout.html";
        }

        EmployeeDetailsDTO employee = currentEmployee();
        model.addAttribute("view", attendanceSelfService.build(employee.getId(), employee.getFullName()));
        return "attendance-self :: content";
    }

    /** Record today's check-in for the authenticated employee, then re-render the card. */
    @PostMapping("/htmx/attendance/check-in")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String checkIn(
            @RequestParam(value = "notes", required = false) String notes,
            Model model) {

        EmployeeDetailsDTO employee = currentEmployee();
        attendanceService.checkIn(employee.getId(), notes);
        model.addAttribute("view", attendanceSelfService.build(employee.getId(), employee.getFullName()));
        return "attendance-self :: card";
    }

    /** Record today's check-out for the authenticated employee, then re-render the card. */
    @PostMapping("/htmx/attendance/check-out")
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    public String checkOut(
            @RequestParam(value = "notes", required = false) String notes,
            Model model) {

        EmployeeDetailsDTO employee = currentEmployee();
        attendanceService.checkOut(employee.getId(), notes);
        model.addAttribute("view", attendanceSelfService.build(employee.getId(), employee.getFullName()));
        return "attendance-self :: card";
    }

    /**
     * Renders check-in/out failures as an in-place HTML fragment instead of letting them
     * fall through to the JSON {@code GlobalExceptionHandler}. The common case is the
     * double-click race: the service throws {@link IllegalStateException} ("Already checked
     * in today" / "Already checked out today" / "You haven't checked in today") — that
     * should show the user a banner, not a 500. Returns HTTP 200 with the freshly-rebuilt
     * card so HTMX performs the swap (it skips swaps on 4xx/5xx by default).
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public String handleActionFailure(RuntimeException ex, Model model) {
        EmployeeDetailsDTO employee = currentEmployee();
        model.addAttribute("view", attendanceSelfService.build(employee.getId(), employee.getFullName()));
        model.addAttribute("error", ex.getMessage());
        return "attendance-self :: card";
    }

    /** Resolve the authenticated user's own Employee record — the only id this page acts on. */
    private EmployeeDetailsDTO currentEmployee() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return employeeService.findByEmail(email);
    }
}
