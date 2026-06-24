package com.admtechhub.maestrohr.search;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.*;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeService employeeService;   // to get current user's employee profile

    public SearchResponse search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) {
            return new SearchResponse(List.of());
        }

        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        String term = normalized;
        List<SearchResult> results = new ArrayList<>();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = auth.getAuthorities().stream()
                .map(g -> g.getAuthority().replace("ROLE_", ""))
                .collect(Collectors.toSet());

        // Identify current employee (if any)
        Employee currentEmployee = null;
        try {
            EmployeeDetailsDTO dto = employeeService.findByEmail(auth.getName());
            currentEmployee = employeeRepository.findById(dto.getId()).orElse(null);
        } catch (Exception ignored) {
            // user may not be an employee (e.g. pure HR_ADMIN without profile)
        }

        // ── EMPLOYEE role ────────────────────────────────────────
        if (roles.contains("EMPLOYEE") && roles.size() == 1) {   // pure employee
            if (currentEmployee != null) {
                // Only own employee record if name/email matches term
                if (matchesEmployee(currentEmployee, term)) {
                    results.add(toSearchResult(currentEmployee));
                }
                // Own leave requests
                leaveRequestRepository.findByEmployeeId(currentEmployee.getId(),
                                PageRequest.of(0, 5)).stream()
                        .filter(lr -> matchesLeave(lr, term))
                        .forEach(lr -> results.add(toLeaveResult(lr)));
            }
            return new SearchResponse(results.stream().limit(15).toList());
        }

        // ── DEPT_MANAGER role ───────────────────────────────────
        if (roles.contains("DEPT_MANAGER")) {
            if (currentEmployee != null && currentEmployee.getDepartment() != null) {
                UUID deptId = currentEmployee.getDepartment().getId();

                // Employees in same department (or match term)
                List<Employee> deptEmployees = employeeRepository.findByDepartmentId(deptId,
                                PageRequest.of(0, 20)).stream()
                        .filter(e -> matchesEmployee(e, term))
                        .collect(Collectors.toList());
                deptEmployees.forEach(e -> results.add(toSearchResult(e)));

                // Leave requests for those employees
                Set<UUID> empIds = deptEmployees.stream().map(Employee::getId).collect(Collectors.toSet());
                if (!empIds.isEmpty()) {
                    leaveRequestRepository.findByEmployeeIdIn(empIds, PageRequest.of(0, 10)).stream()
                            .filter(lr -> matchesLeave(lr, term))
                            .forEach(lr -> results.add(toLeaveResult(lr)));
                }
            }

            // Departments, pay grades, payroll – tenant‑scoped (already fine)
            departmentRepository.searchDepartments(tenantId, term, PageRequest.of(0, 4))
                    .forEach(dept -> results.add(toDeptResult(dept)));
            payGradeRepository.searchPayGrades(tenantId, term, PageRequest.of(0, 4))
                    .forEach(grade -> results.add(toPayGradeResult(grade)));
            payrollRunRepository.searchPayrollRuns(tenantId, term, PageRequest.of(0, 5))
                    .forEach(run -> results.add(toPayrollResult(run)));

            return new SearchResponse(results.stream().limit(15).toList());
        }

        // ── HR_ADMIN / FINANCE_OFFICER / SUPER_ADMIN ────────────
        // Full search (unchanged original logic)
        employeeRepository.searchEmployees(term, PageRequest.of(0, 6))
                .forEach(emp -> results.add(toSearchResult(emp)));

        departmentRepository.searchDepartments(tenantId, term, PageRequest.of(0, 4))
                .forEach(dept -> results.add(toDeptResult(dept)));

        payGradeRepository.searchPayGrades(tenantId, term, PageRequest.of(0, 4))
                .forEach(grade -> results.add(toPayGradeResult(grade)));

        payrollRunRepository.searchPayrollRuns(tenantId, term, PageRequest.of(0, 5))
                .forEach(run -> results.add(toPayrollResult(run)));

        leaveRequestRepository.searchLeaveRequests(tenantId, term, PageRequest.of(0, 5))
                .forEach(req -> {
                    String employeeName = req.getEmployee().getFullName();
                    String details = req.getLeaveType().getName() + " · " + req.getStatus().name();
                    results.add(new SearchResult("Leave", employeeName, details, "/leave"));
                });

        return new SearchResponse(results.stream().limit(15).toList());
    }

    // ── Helper predicates ────────────────────────────────────────
    private boolean matchesEmployee(Employee emp, String term) {
        String lower = term.toLowerCase();
        return (emp.getFirstName() + " " + emp.getLastName()).toLowerCase().contains(lower)
                || emp.getEmail().toLowerCase().contains(lower)
                || emp.getEmployeeNumber().toLowerCase().contains(lower);
    }

    private boolean matchesLeave(LeaveRequest lr, String term) {
        String lower = term.toLowerCase();
        return lr.getEmployee().getFullName().toLowerCase().contains(lower)
                || lr.getLeaveType().getName().toLowerCase().contains(lower)
                || lr.getStatus().name().toLowerCase().contains(lower);
    }

    // ── Mapping helpers ─────────────────────────────────────────
    private SearchResult toSearchResult(Employee emp) {
        return new SearchResult("Employee",
                emp.getFullName(),
                emp.getEmployeeNumber() + " · " + emp.getEmail(),
                "/htmx/employee-view?id=" + emp.getId());
    }

    private SearchResult toDeptResult(Department dept) {
        return new SearchResult("Department", dept.getName(), "Department", "/departments");
    }

    private SearchResult toPayGradeResult(PayGrade grade) {
        return new SearchResult("Pay Grade", grade.getName(),
                "Gross salary " + grade.getGrossSalary(), "/pay-grades");
    }

    private SearchResult toPayrollResult(PayrollRun run) {
        return new SearchResult("Payroll", run.getPeriod(),
                run.getStatus().name(), "/payroll/" + run.getId());
    }

    private SearchResult toLeaveResult(LeaveRequest lr) {
        return new SearchResult("Leave",
                lr.getEmployee().getFullName(),
                lr.getLeaveType().getName() + " · " + lr.getStatus().name(),
                "/leave");
    }

    // ── DTOs ────────────────────────────────────────────────────
    public record SearchResponse(List<SearchResult> results) {}
    public record SearchResult(String type, String title, String subtitle, String url) {}
}