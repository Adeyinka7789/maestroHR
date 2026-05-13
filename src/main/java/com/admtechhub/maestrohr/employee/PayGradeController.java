package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pay-grades")
@RequiredArgsConstructor
public class PayGradeController {

    private final PayGradeService payGradeService;

    @Data
    static class PayGradeRequest {
        @NotBlank(message = "Grade name is required")
        private String name;

        @NotNull(message = "Basic salary is required")
        @Positive(message = "Basic salary must be positive")
        private Long basicSalary;

        @NotNull
        private Long housingAllowance;

        @NotNull
        private Long transportAllowance;

        @NotNull
        private Long otherAllowances;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PayGrade>> create(
            @Valid @RequestBody PayGradeRequest request) {
        PayGrade grade = payGradeService.create(
                request.getName(),
                request.getBasicSalary(),
                request.getHousingAllowance(),
                request.getTransportAllowance(),
                request.getOtherAllowances()
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Pay grade created", grade));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<PayGrade>>> findAll() {
        return ResponseEntity.ok(
                ApiResponse.success("Pay grades retrieved",
                        payGradeService.findAllActive()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PayGrade>> update(
            @PathVariable UUID id,
            @Valid @RequestBody PayGradeRequest request) {
        PayGrade updated = payGradeService.update(
                id,
                request.getName(),
                request.getBasicSalary(),
                request.getHousingAllowance(),
                request.getTransportAllowance(),
                request.getOtherAllowances()
        );
        return ResponseEntity.ok(ApiResponse.success("Pay grade updated", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        payGradeService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Pay grade deactivated"));
    }
}