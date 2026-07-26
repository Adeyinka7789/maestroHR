package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeImportService employeeImportService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeDetailsDTO>> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        EmployeeDetailsDTO created = employeeService.createEmployee(request);
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
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "User not authenticated"));
        }

        String email = userDetails.getUsername();
        EmployeeDetailsDTO employee = employeeService.findByEmail(email);
        if (employee == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Employee not found for email: " + email));
        }

        return ResponseEntity.ok(Map.of("success", true, "data", employee));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeDetailsDTO>> getEmployeeById(@PathVariable UUID id) {
        EmployeeDetailsDTO employee = employeeService.getEmployeeById(id);
        return ResponseEntity.ok(ApiResponse.success("Employee retrieved successfully", employee));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeDetailsDTO>> updateEmployee(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEmployeeRequest request) {
        EmployeeDetailsDTO updated = employeeService.updateEmployee(id, request);
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

    /**
     * Probation → Confirmation: one-click trigger that marks the employee as confirmed
     * (unlocking pay-grade changes). HR/super-admin only; idempotent on the service side.
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeDetailsDTO>> confirmEmployee(@PathVariable UUID id) {
        EmployeeDetailsDTO confirmed = employeeService.confirmEmployee(id);
        return ResponseEntity.ok(ApiResponse.success("Employee confirmed", confirmed));
    }

    @GetMapping("/export/excel")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportEmployeesToExcel() {
        byte[] excelData = employeeService.exportEmployeesToExcel();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("employees-" + LocalDate.now() + ".xlsx")
                .build());
        return ResponseEntity.ok().headers(headers).body(excelData);
    }

    /**
     * STEP 1 — Parse + validate the uploaded file and return a preview. Performs no database
     * writes. The frontend renders the per-row outcome (valid / warning / error) for the user to
     * review before confirming.
     */
    @PostMapping("/import/preview")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ImportPreviewResult>> previewImport(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Uploaded file is empty."));
        }
        ImportPreviewResult preview = employeeImportService.preview(file);
        return ResponseEntity.ok(ApiResponse.success("Preview generated", preview));
    }

    /**
     * STEP 2 — Actually import the file. Re-parses and re-validates against the live database,
     * then writes every non-error row, each in its own transaction. Returns a summary reflecting
     * what was truly committed.
     */
    @PostMapping("/import/confirm")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ImportSummaryResult>> confirmImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "createMissingDepartments", defaultValue = "true") boolean createMissingDepartments) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Uploaded file is empty."));
        }
        ImportSummaryResult summary = employeeImportService.confirmImport(file, createMissingDepartments);
        return ResponseEntity.ok(ApiResponse.success("Import processed", summary));
    }

    /**
     * Legacy one-shot import (kept for backward compatibility): parse + validate + import in a
     * single call, skipping the preview step. Backed by the same robust pipeline.
     */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ImportSummaryResult>> importEmployees(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Uploaded file is empty."));
        }
        ImportSummaryResult summary = employeeImportService.importInOneShot(file);
        return ResponseEntity.ok(ApiResponse.success("Import processed successfully", summary));
    }

    /**
     * Download a clean CSV template with the exact headers the importer recognises, plus one
     * example row to illustrate the expected format.
     */
    @GetMapping("/import/template")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] csv = employeeImportService.buildCsvTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("employee-import-template.csv")
                .build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }
}