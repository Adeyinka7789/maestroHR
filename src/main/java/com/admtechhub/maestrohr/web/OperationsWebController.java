package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceRecord;
import com.admtechhub.maestrohr.attendance.AttendanceRepository;
import com.admtechhub.maestrohr.attendance.AttendanceStatus;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.employee.PayGradeRepository;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.leave.LeaveTypeRepository;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.reporting.ReportFile;
import com.admtechhub.maestrohr.reporting.ReportingService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.YearMonth;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@RequestMapping
@Transactional(readOnly = true)
public class OperationsWebController {

    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final AttendanceRepository attendanceRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final ReportingService reportingService;

    @GetMapping("/departments")
    public String departments(Model model) {
        List<Department> departments = departmentRepository.findAll().stream()
                .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        model.addAttribute("pageTitle", "Departments");
        model.addAttribute("pageSubtitle", "Team structures and reporting lines");
        model.addAttribute("departments", departments);
        model.addAttribute("employeeRepository", employeeRepository);
        return "redirect:/departments.html";
    }

    @GetMapping("/pay-grades")
    public String payGrades(Model model) {
        List<PayGrade> payGrades = payGradeRepository.findAll().stream()
                .sorted(Comparator.comparing(PayGrade::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        model.addAttribute("pageTitle", "Pay Grades");
        model.addAttribute("pageSubtitle", "Compensation bands and allowances");
        model.addAttribute("payGrades", payGrades);
        return "redirect:/pay-grades.html";
    }

    @GetMapping("/leave")
    public String leave(Model model) {
        List<LeaveRequest> requests = leaveRequestRepository.findAll().stream()
                .sorted(Comparator.comparing(LeaveRequest::getCreatedAt).reversed())
                .limit(20)
                .peek(request -> {
                    request.getEmployee().getFullName();
                    request.getLeaveType().getName();
                })
                .toList();

        model.addAttribute("pageTitle", "Leave Management");
        model.addAttribute("pageSubtitle", "Requests, balances, and leave types");
        model.addAttribute("leaveTypes", leaveTypeRepository.findByOrderByNameAsc());
        model.addAttribute("leaveRequests", requests);
        model.addAttribute("employees", employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getLastName, String.CASE_INSENSITIVE_ORDER))
                .toList());
        model.addAttribute("pendingCount", leaveRequestRepository.findByStatus(LeaveStatus.PENDING).size());
        return "redirect:/leave.html";
    }

    @GetMapping("/attendance")
    public String attendance(Model model) {
        LocalDate today = LocalDate.now();
        List<AttendanceRecord> records = attendanceRepository.findByAttendanceDate(today).stream()
                .peek(record -> record.getEmployee().getFullName())
                .sorted(Comparator.comparing(record -> record.getEmployee().getLastName(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        model.addAttribute("pageTitle", "Attendance");
        model.addAttribute("pageSubtitle", "Daily presence and exceptions");
        model.addAttribute("today", today);
        model.addAttribute("records", records);
        model.addAttribute("presentCount", records.stream().filter(r -> r.getStatus() == AttendanceStatus.PRESENT).count());
        model.addAttribute("lateCount", records.stream().filter(r -> r.getStatus() == AttendanceStatus.LATE).count());
        model.addAttribute("absentCount", records.stream().filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count());
        return "redirect:/attendance.html";
    }

    @GetMapping("/payroll")
    public String payroll(Model model) {
        UUID tenantId = currentTenantId();
        List<PayrollRun> payrollRuns = payrollRunRepository.findAllByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .peek(run -> {
                    if (run.getInitiatedBy() != null) {
                        run.getInitiatedBy().getEmail();
                    }
                    if (run.getApprovedBy() != null) {
                        run.getApprovedBy().getEmail();
                    }
                })
                .toList();

        model.addAttribute("pageTitle", "Payroll Runs");
        model.addAttribute("pageSubtitle", "Compute, review, approve, and disburse");
        model.addAttribute("payrollRuns", payrollRuns);
        model.addAttribute("latestPayrollRun", payrollRunRepository.findTopByTenant_IdOrderByCreatedAtDesc(tenantId).orElse(null));
        model.addAttribute("pendingApprovals", payrollRunRepository.findByTenant_IdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL).size());
        return "redirect:/payroll.html";
    }

    @GetMapping("/payroll/{id}")
    public String payrollDetails(@PathVariable UUID id, Model model) {
        PayrollRun payrollRun = payrollRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + id));
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(id).stream()
                .peek(entry -> entry.getEmployee().getFullName())
                .sorted(Comparator.comparing(entry -> entry.getEmployee().getLastName(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (payrollRun.getInitiatedBy() != null) {
            payrollRun.getInitiatedBy().getEmail();
        }
        if (payrollRun.getApprovedBy() != null) {
            payrollRun.getApprovedBy().getEmail();
        }

        model.addAttribute("pageTitle", "Payroll Details");
        model.addAttribute("pageSubtitle", payrollRun.getPeriod());
        model.addAttribute("payrollRun", payrollRun);
        model.addAttribute("entries", entries);
        return "redirect:/payroll-detail.html";
    }

    @GetMapping("/reports")
    public String reports(Model model) {
        UUID tenantId = currentTenantId();
        List<Employee> employees = employeeRepository.findAll();
        List<PayrollRun> payrollRuns = payrollRunRepository.findAllByTenant_IdOrderByCreatedAtDesc(tenantId).stream().limit(6).toList();

        model.addAttribute("pageTitle", "Reports");
        model.addAttribute("pageSubtitle", "Operational snapshots across HR and payroll");
        model.addAttribute("employees", employees);
        model.addAttribute("payrollRuns", payrollRuns);
        model.addAttribute("leavePending", leaveRequestRepository.findByStatus(LeaveStatus.PENDING).size());
        model.addAttribute("departmentsCount", departmentRepository.count());
        model.addAttribute("currentMonth", YearMonth.now().getMonthValue());
        model.addAttribute("currentYear", YearMonth.now().getYear());
        model.addAttribute("auditFrom", LocalDate.now().minusDays(30));
        model.addAttribute("auditTo", LocalDate.now());
        return "redirect:/reports.html";
    }

    @GetMapping("/subscribers")
    public String subscribers(Model model) {
        List<Tenant> tenants = tenantRepository.findAllByOrderByCreatedAtDesc();
        long activeSubscribers = tenants.stream().filter(Tenant::isActive).count();
        long trialSubscribers = tenants.stream().filter(tenant -> tenant.getSubscriptionPlan() == com.admtechhub.maestrohr.tenant.SubscriptionPlan.FREE_TRIAL).count();
        model.addAttribute("pageTitle", "Subscribers");
        model.addAttribute("pageSubtitle", "Tenant accounts, plans, and lifecycle controls");
        model.addAttribute("tenants", tenants);
        model.addAttribute("users", userRepository.findAll());
        model.addAttribute("activeSubscribers", activeSubscribers);
        model.addAttribute("trialSubscribers", trialSubscribers);
        return "redirect:/subscribers.html";
    }

    @GetMapping("/reports/payslip")
    public ResponseEntity<byte[]> payslipDownload(@RequestParam UUID employeeId,
                                                  @RequestParam(required = false) UUID payrollRunId) {
        ReportFile file = payrollRunId != null
                ? reportingService.generatePayslip(employeeId, payrollRunId)
                : reportingService.generateLatestPayslip(employeeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        UUID tenantId = currentTenantId();
        List<User> users = userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt).reversed())
                .toList();
        List<Tenant> tenants = tenantRepository.findAllByOrderByCreatedAtDesc();

        model.addAttribute("pageTitle", "Admin");
        model.addAttribute("pageSubtitle", "Control panel for tenants, users, and platform operations");
        model.addAttribute("users", users);
        model.addAttribute("tenants", tenants);
        model.addAttribute("roles", com.admtechhub.maestrohr.auth.UserRole.values());
        model.addAttribute("plans", com.admtechhub.maestrohr.tenant.SubscriptionPlan.values());
        model.addAttribute("lockedUsers", users.stream().filter(User::isLocked).count());
        model.addAttribute("activeTenants", tenants.stream().filter(Tenant::isActive).count());
        model.addAttribute("activeEmployees", employeeRepository.countByStatus(com.admtechhub.maestrohr.employee.EmployeeStatus.ACTIVE));
        model.addAttribute("pendingPayrolls", payrollRunRepository.findByTenant_IdAndStatus(tenantId, PayrollStatus.PENDING_APPROVAL).size());
        return "redirect:/admin.html";
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available for operations page");
        }
        return UUID.fromString(tenantId);
    }
}
