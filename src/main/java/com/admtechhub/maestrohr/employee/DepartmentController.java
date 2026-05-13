package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @Data
    static class DepartmentRequest {
        @NotBlank(message = "Department name is required")
        private String name;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Department>> create(
            @Valid @RequestBody DepartmentRequest request) {
        Department department = departmentService.create(request.getName());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Department created", department));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<Department>>> findAll() {
        return ResponseEntity.ok(
                ApiResponse.success("Departments retrieved",
                        departmentService.findAll()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Department>> update(
            @PathVariable UUID id,
            @Valid @RequestBody DepartmentRequest request) {
        Department updated = departmentService.update(id, request.getName());
        return ResponseEntity.ok(
                ApiResponse.success("Department updated", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        departmentService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Department deleted"));
    }
}