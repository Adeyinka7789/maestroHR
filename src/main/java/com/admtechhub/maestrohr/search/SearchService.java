package com.admtechhub.maestrohr.search;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.PayGradeRepository;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        String term = normalized.toLowerCase(Locale.ROOT);
        List<SearchResult> results = new ArrayList<>();

        employeeRepository.searchEmployees(normalized, PageRequest.of(0, 6)).getContent().forEach(employee ->
                results.add(new SearchResult(
                        "Employee",
                        employee.getFullName(),
                        employee.getEmployeeNumber() + " · " + employee.getEmail(),
                        "/employees/" + employee.getId()
                )));

        departmentRepository.findAllByTenantId(tenantId).stream()
                .filter(department -> department.getName().toLowerCase(Locale.ROOT).contains(term))
                .limit(4)
                .forEach(department -> results.add(new SearchResult(
                        "Department",
                        department.getName(),
                        "Department directory",
                        "/departments"
                )));

        payGradeRepository.findAllByTenantIdAndIsActive(tenantId, true).stream()
                .filter(grade -> grade.getName().toLowerCase(Locale.ROOT).contains(term))
                .limit(4)
                .forEach(grade -> results.add(new SearchResult(
                        "Pay Grade",
                        grade.getName(),
                        "Gross salary " + grade.getGrossSalary(),
                        "/pay-grades"
                )));

        payrollRunRepository.findAllByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .filter(run -> run.getPeriod().toLowerCase(Locale.ROOT).contains(term) || run.getStatus().name().toLowerCase(Locale.ROOT).contains(term))
                .limit(5)
                .forEach(run -> results.add(new SearchResult(
                        "Payroll",
                        run.getPeriod(),
                        run.getStatus().name(),
                        "/payroll/" + run.getId()
                )));

        leaveRequestRepository.findAll().stream()
                .filter(request -> matchesLeave(request, term))
                .limit(5)
                .forEach(request -> {
                    Employee employee = request.getEmployee();
                    results.add(new SearchResult(
                            "Leave",
                            employee.getFullName(),
                            request.getLeaveType().getName() + " · " + request.getStatus().name(),
                            "/leave"
                    ));
                });

        return new SearchResponse(results.stream().limit(15).toList());
    }

    private boolean matchesLeave(com.admtechhub.maestrohr.leave.LeaveRequest request, String term) {
        return request.getEmployee().getFullName().toLowerCase(Locale.ROOT).contains(term)
                || request.getLeaveType().getName().toLowerCase(Locale.ROOT).contains(term)
                || request.getStatus().name().toLowerCase(Locale.ROOT).contains(term)
                || request.getReason().toLowerCase(Locale.ROOT).contains(term);
    }

    public record SearchResponse(List<SearchResult> results) {}

    public record SearchResult(String type, String title, String subtitle, String url) {}
}
