package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<Employee>> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        Employee created = employeeService.createEmployee(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Employee created successfully", created));
    }

    // The rest of your endpoints remain the same...
    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Page<Employee>>> getAllEmployees(
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<Employee> employees = employeeService.getAllEmployees(pageable);
        return ResponseEntity.ok(ApiResponse.success("Employees retrieved successfully", employees));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponse<Employee>> getEmployeeById(@PathVariable UUID id) {
        Employee employee = employeeService.getEmployeeById(id);
        return ResponseEntity.ok(ApiResponse.success("Employee retrieved successfully", employee));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<Employee>> updateEmployee(
            @PathVariable UUID id,
            @Valid @RequestBody EmployeeRequest request) {
        Employee updated = employeeService.updateEmployee(id, request);
        return ResponseEntity.ok(ApiResponse.success("Employee updated successfully", updated));
    }

    @DeleteMapping("/{id}/terminate")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> terminateEmployee(
            @PathVariable UUID id,
            @RequestParam LocalDate terminationDate) {
        employeeService.terminateEmployee(id, terminationDate);
        return ResponseEntity.ok(ApiResponse.success("Employee terminated successfully", null));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Page<Employee>>> searchEmployees(
            @RequestParam String query,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Employee> results = employeeService.searchEmployees(query, pageable);
        return ResponseEntity.ok(ApiResponse.success("Search results", results));
    }

    @GetMapping("/stats/active-count")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getActiveEmployeeCount() {
        long count = employeeService.countActiveEmployees();
        return ResponseEntity.ok(ApiResponse.success("Active employee count", Map.of("count", count)));
    }
}