package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.User;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Employee>> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        Employee created = employeeService.createEmployee(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Employee created successfully", created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<EmployeeSummaryDTO>>> getAllEmployees(
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<Employee> employees = employeeService.getAllEmployees(pageable);
        Page<EmployeeSummaryDTO> employeeDTOs = employees.map(EmployeeSummaryDTO::new);
        return ResponseEntity.ok(ApiResponse.success("Employees retrieved successfully", employeeDTOs));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<EmployeeSummaryDTO>>> searchEmployees(
            @RequestParam String query,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<Employee> results = employeeService.searchEmployees(query, pageable);
        Page<EmployeeSummaryDTO> employeeDTOs = results.map(EmployeeSummaryDTO::new);
        return ResponseEntity.ok(ApiResponse.success("Search results", employeeDTOs));
    }

    @GetMapping("/stats/active-count")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getActiveEmployeeCount() {
        long count = employeeService.countActiveEmployees();
        return ResponseEntity.ok(ApiResponse.success("Active employee count", Map.of("count", count)));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<?> getCurrentEmployee(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "User not authenticated");
            return ResponseEntity.status(401).body(error);
        }

        String email = userDetails.getUsername();
        Employee employee = employeeService.findByEmail(email);
        if (employee == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Employee not found for email: " + email);
            return ResponseEntity.status(404).body(error);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", new EmployeeSummaryDTO(employee));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<EmployeeSummaryDTO>> getEmployeeById(@PathVariable UUID id) {
        Employee employee = employeeService.getEmployeeById(id);
        return ResponseEntity.ok(ApiResponse.success("Employee retrieved successfully", new EmployeeSummaryDTO(employee)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Employee>> updateEmployee(
            @PathVariable UUID id,
            @Valid @RequestBody EmployeeRequest request) {
        Employee updated = employeeService.updateEmployee(id, request);
        return ResponseEntity.ok(ApiResponse.success("Employee updated successfully", updated));
    }

    @DeleteMapping("/{id}/terminate")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> terminateEmployee(
            @PathVariable UUID id,
            @RequestParam LocalDate terminationDate) {
        employeeService.terminateEmployee(id, terminationDate);
        return ResponseEntity.ok(ApiResponse.success("Employee terminated successfully", null));
    }
}
