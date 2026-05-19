package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Employee self-service routes.
 * All data is fetched client-side via REST APIs.
 * Server-side model attributes removed — they were discarded by the redirect.
 */
@Controller
@RequestMapping("/employee")
@RequiredArgsConstructor
public class EmployeeDashboardController {

    private final EmployeeService employeeService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final LeaveService leaveService;

    @GetMapping("/dashboard")
    public String dashboard(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/employee-dashboard.html" : "forward:/layout.html";
    }

    @GetMapping("/profile")
    public String profile(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/employee-view.html" : "forward:/layout.html";
    }

    @GetMapping("/leave")
    public String leaveRequests(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/leave.html" : "forward:/layout.html";
    }

    @GetMapping("/payslips")
    public String payslips(
            @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return htmx != null ? "forward:/reports.html" : "forward:/layout.html";
    }

    // ── POST: leave submission (unchanged — real server work) ─────────────────

    @PostMapping("/leave/submit")
    public String submitLeaveRequest(
            @RequestParam UUID leaveTypeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam String reason) {
        Employee employee = getCurrentEmployee();
        leaveService.submitLeaveRequest(employee.getId(), leaveTypeId, startDate, endDate, reason, null);
        return "redirect:/employee/leave";
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Employee getCurrentEmployee() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return employeeService.findByEmail(email);
    }
}