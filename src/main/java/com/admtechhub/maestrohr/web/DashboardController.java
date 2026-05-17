package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRunRepository payrollRunRepository;

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        UUID tenantId = currentTenantId();
        // Get current user info
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String userEmail = authentication != null ? authentication.getName() : "";
        String userRole = authentication != null ? authentication.getAuthorities().toString() : "";

        // Stats
        long totalEmployees = employeeRepository.count();
        long activeEmployees = employeeRepository.countByStatus(EmployeeStatus.ACTIVE);
        long pendingLeaveRequests = leaveRequestRepository.findByStatus(LeaveStatus.PENDING).size();

        // Pending approvals
        List<LeaveRequest> pendingLeaves = leaveRequestRepository.findByStatus(LeaveStatus.PENDING);
        List<PayrollRun> pendingPayrolls = payrollRunRepository.findByTenant_IdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL);

        // This month's payroll
        java.time.YearMonth now = java.time.YearMonth.now();
        var thisMonthPayroll = payrollRunRepository
                .findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(tenantId, now.getMonthValue(), now.getYear())
                .orElse(null);

        // Chart data - last 6 months payroll totals
        Map<String, Long> monthlyTotals = new HashMap<>();
        for (int i = 5; i >= 0; i--) {
            java.time.YearMonth ym = now.minusMonths(i);
            var payroll = payrollRunRepository.findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(
                    tenantId, ym.getMonthValue(), ym.getYear()
            ).orElse(null);
            monthlyTotals.put(ym.toString(), payroll != null ? payroll.getTotalNet() / 100 : 0L);
        }
        Map<String, Long> departmentHeadcounts = new LinkedHashMap<>();
        departmentRepository.findAll().forEach(department ->
                departmentHeadcounts.put(
                        department.getName(),
                        (long) employeeRepository.findByDepartmentId(department.getId()).size()
                ));
        Map<String, Long> filteredDepartmentHeadcounts = departmentHeadcounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole.replace("[", "").replace("]", ""));
        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("title", "Dashboard");

        model.addAttribute("totalEmployees", totalEmployees);
        model.addAttribute("activeEmployees", activeEmployees);
        model.addAttribute("pendingLeaveRequests", pendingLeaveRequests);
        model.addAttribute("pendingLeaves", pendingLeaves);
        model.addAttribute("pendingPayrolls", pendingPayrolls);
        model.addAttribute("thisMonthPayroll", thisMonthPayroll);
        model.addAttribute("monthlyTotals", monthlyTotals);
        model.addAttribute("departmentHeadcounts", filteredDepartmentHeadcounts);

        return "redirect:/dashboard.html";
    }

    @GetMapping("/")
    public String home() {
        return "home";
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available for dashboard");
        }
        return UUID.fromString(tenantId);
    }
}
