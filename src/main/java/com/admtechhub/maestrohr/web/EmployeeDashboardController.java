package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/employee")
@RequiredArgsConstructor
public class EmployeeDashboardController {

    private final EmployeeService employeeService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final LeaveService leaveService;

    private Employee getCurrentEmployee() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return employeeService.findByEmail(email);
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        Employee employee = getCurrentEmployee();

        // Get recent payslips (top 3)
        List<PayrollEntry> recentPayslips = payrollEntryRepository
                .findTop3ByEmployeeIdOrderByPayrollRunCreatedAtDesc(employee.getId(), Pageable.ofSize(3));

        // Get pending leave requests
        List<LeaveRequest> pendingRequests = leaveRequestRepository
                .findByEmployeeIdAndStatus(employee.getId(), LeaveStatus.PENDING);

        // Get leave balance for current year (simplified)
        int currentYear = LocalDate.now().getYear();

        model.addAttribute("pageTitle", "Employee Dashboard");
        model.addAttribute("employee", employee);
        model.addAttribute("recentPayslips", recentPayslips);
        model.addAttribute("pendingRequests", pendingRequests);
        model.addAttribute("currentYear", currentYear);

        return "redirect:/employee-dashboard.html";
    }

    @GetMapping("/profile")
    public String profile(Model model) {
        Employee employee = getCurrentEmployee();
        model.addAttribute("pageTitle", "My Profile");
        model.addAttribute("employee", employee);
        return "redirect:/employee-view.html";
    }

    @GetMapping("/leave")
    public String leaveRequests(Model model) {
        Employee employee = getCurrentEmployee();
        List<LeaveRequest> requests = leaveRequestRepository.findByEmployeeId(employee.getId());

        model.addAttribute("pageTitle", "My Leave Requests");
        model.addAttribute("requests", requests);
        model.addAttribute("employee", employee);
        return "redirect:/leave.html";
    }

    @PostMapping("/leave/submit")
    public String submitLeaveRequest(@RequestParam UUID leaveTypeId,
                                     @RequestParam LocalDate startDate,
                                     @RequestParam LocalDate endDate,
                                     @RequestParam String reason) {
        Employee employee = getCurrentEmployee();
        leaveService.submitLeaveRequest(employee.getId(), leaveTypeId, startDate, endDate, reason, null);
        return "redirect:/employee/leave";
    }

    @GetMapping("/payslips")
    public String payslips(Model model) {
        Employee employee = getCurrentEmployee();
        List<PayrollEntry> payslips = payrollEntryRepository
                .findByEmployeeIdOrderByPayrollRunCreatedAtDesc(employee.getId());

        model.addAttribute("pageTitle", "My Payslips");
        model.addAttribute("payslips", payslips);
        model.addAttribute("employee", employee);
        return "redirect:/reports.html";
    }
}