package com.admtechhub.maestrohr.search;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.*;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public SearchResponse search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 2) {
            return new SearchResponse(List.of());
        }

        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        String term = normalized;
        List<SearchResult> results = new ArrayList<>();

        // Employees – already efficient (uses DB LIKE via existing method)
        employeeRepository.searchEmployees(term, PageRequest.of(0, 6))
                .forEach(emp -> results.add(new SearchResult(
                        "Employee",
                        emp.getFullName(),
                        emp.getEmployeeNumber() + " · " + emp.getEmail(),
                        "/htmx/employee-view?id=" + emp.getId()
                )));

        // Departments – new DB query
        departmentRepository.searchDepartments(tenantId, term, PageRequest.of(0, 4))
                .forEach(dept -> results.add(new SearchResult(
                        "Department",
                        dept.getName(),
                        "Department",
                        "/departments"
                )));

        // Pay grades – new DB query
        payGradeRepository.searchPayGrades(tenantId, term, PageRequest.of(0, 4))
                .forEach(grade -> results.add(new SearchResult(
                        "Pay Grade",
                        grade.getName(),
                        "Gross salary " + grade.getGrossSalary(),
                        "/pay-grades"
                )));

        // Payroll runs – new DB query
        payrollRunRepository.searchPayrollRuns(tenantId, term, PageRequest.of(0, 5))
                .forEach(run -> results.add(new SearchResult(
                        "Payroll",
                        run.getPeriod(),
                        run.getStatus().name(),
                        "/payroll/" + run.getId()
                )));

        // Leave requests – new DB query (now tenant‑filtered)
        leaveRequestRepository.searchLeaveRequests(tenantId, term, PageRequest.of(0, 5))
                .forEach(req -> {
                    String employeeName = req.getEmployee().getFullName();
                    String details = req.getLeaveType().getName() + " · " + req.getStatus().name();
                    results.add(new SearchResult(
                            "Leave",
                            employeeName,
                            details,
                            "/leave"
                    ));
                });

        return new SearchResponse(results.stream().limit(15).toList());
    }

    public record SearchResponse(List<SearchResult> results) {}
    public record SearchResult(String type, String title, String subtitle, String url) {}
}