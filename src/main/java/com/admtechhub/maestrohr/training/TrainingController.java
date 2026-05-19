package com.admtechhub.maestrohr.training;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/training")
@RequiredArgsConstructor
@Slf4j
public class TrainingController {

    private final TrainingService trainingService;

    // Training Programs
    @GetMapping("/programs")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Page<TrainingProgram>>> getPrograms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success("Programs retrieved", trainingService.getTrainingPrograms(pageable)));
    }

    @PostMapping("/programs")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<TrainingProgram>> createProgram(@RequestBody TrainingProgram program) {
        TrainingProgram created = trainingService.createTrainingProgram(program);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Program created", created));
    }

    @DeleteMapping("/programs/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteProgram(@PathVariable UUID id) {
        trainingService.deleteTrainingProgram(id);
        return ResponseEntity.ok(ApiResponse.success("Program deleted", null));
    }

    // Stats
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success("Stats retrieved", trainingService.getDashboardStats()));
    }

    // ==================== CERTIFICATION ENDPOINTS ====================

    @GetMapping("/certifications")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN', 'DEPT_MANAGER')")
    public ResponseEntity<ApiResponse<Page<Certification>>> getCertifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success("Certifications retrieved", trainingService.getCertifications(pageable)));
    }

    @PostMapping("/certifications")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Certification>> addCertification(@RequestBody Certification certification) {
        Certification created = trainingService.addCertification(certification);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Certification added", created));
    }

    @DeleteMapping("/certifications/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteCertification(@PathVariable UUID id) {
        trainingService.deleteCertification(id);
        return ResponseEntity.ok(ApiResponse.success("Certification deleted", null));
    }
}