package com.admtechhub.maestrohr.recruitment;

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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/recruitment")
@RequiredArgsConstructor
@Slf4j
public class RecruitmentController {

    private final RecruitmentService recruitmentService;

    // ==================== JOB POSTING ENDPOINTS ====================

    @GetMapping("/jobs")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<JobPosting>>> getJobPostings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success("Job postings retrieved", recruitmentService.getJobPostings(pageable)));
    }

    @GetMapping("/jobs/published")
    public ResponseEntity<ApiResponse<List<JobPosting>>> getPublishedJobs() {
        return ResponseEntity.ok(ApiResponse.success("Published jobs retrieved", recruitmentService.getPublishedJobs()));
    }

    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobPosting>> getJobPosting(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Job posting retrieved", recruitmentService.getJobPostingById(id)));
    }

    @PostMapping("/jobs")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobPosting>> createJobPosting(@RequestBody JobPosting jobPosting) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        JobPosting created = recruitmentService.createJobPosting(jobPosting, email);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Job posting created", created));
    }

    @PutMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobPosting>> updateJobPosting(@PathVariable UUID id, @RequestBody JobPosting jobPosting) {
        JobPosting updated = recruitmentService.updateJobPosting(id, jobPosting);
        return ResponseEntity.ok(ApiResponse.success("Job posting updated", updated));
    }

    @DeleteMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteJobPosting(@PathVariable UUID id) {
        recruitmentService.deleteJobPosting(id);
        return ResponseEntity.ok(ApiResponse.success("Job posting deleted", null));
    }

    // ==================== JOB APPLICATION ENDPOINTS ====================

    @GetMapping("/applications")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Page<JobApplication>>> getApplications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success("Applications retrieved", recruitmentService.getApplications(pageable)));
    }

    @GetMapping("/jobs/{jobId}/applications")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<JobApplication>>> getApplicationsByJob(@PathVariable UUID jobId) {
        return ResponseEntity.ok(ApiResponse.success("Applications retrieved", recruitmentService.getApplicationsByJob(jobId)));
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobApplication>> getApplication(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Application retrieved", recruitmentService.getApplicationById(id)));
    }

    @PostMapping("/jobs/{jobId}/apply")
    public ResponseEntity<ApiResponse<JobApplication>> submitApplication(
            @PathVariable UUID jobId,
            @RequestParam String applicantName,
            @RequestParam String applicantEmail,
            @RequestParam(required = false) String applicantPhone,
            @RequestParam(required = false) String coverLetter,
            @RequestParam(required = false) MultipartFile resume) {

        JobApplication application = JobApplication.builder()
                .applicantName(applicantName)
                .applicantEmail(applicantEmail)
                .applicantPhone(applicantPhone)
                .coverLetter(coverLetter)
                .build();

        JobApplication submitted = recruitmentService.submitApplication(jobId, application, resume);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Application submitted", submitted));
    }

    @PatchMapping("/applications/{id}/status")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobApplication>> updateApplicationStatus(
            @PathVariable UUID id,
            @RequestParam JobApplication.ApplicationStatus status,
            @RequestParam(required = false) String notes) {
        JobApplication updated = recruitmentService.updateApplicationStatus(id, status, notes);
        return ResponseEntity.ok(ApiResponse.success("Application status updated", updated));
    }

    @PostMapping("/applications/{id}/schedule-interview")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobApplication>> scheduleInterview(
            @PathVariable UUID id,
            @RequestParam LocalDateTime interviewDate,
            @RequestParam(required = false) String notes) {
        JobApplication updated = recruitmentService.scheduleInterview(id, interviewDate, notes);
        return ResponseEntity.ok(ApiResponse.success("Interview scheduled", updated));
    }

    @PostMapping("/applications/{id}/convert-to-employee")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> convertToEmployee(@PathVariable UUID id) {
        com.admtechhub.maestrohr.employee.Employee employee = recruitmentService.convertToEmployee(id);
        Map<String, Object> response = new HashMap<>();
        response.put("employeeId", employee.getId());
        response.put("employeeName", employee.getFullName());
        response.put("message", "Applicant converted to employee successfully");
        return ResponseEntity.ok(ApiResponse.success("Converted to employee", response));
    }
}