package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
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
    public ResponseEntity<ApiResponse<Page<JobPostingDTO>>> getJobPostings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<JobPostingDTO> postings = recruitmentService.getJobPostings(pageable);
        return ResponseEntity.ok(ApiResponse.success("Job postings retrieved", postings));
    }

    @GetMapping("/jobs/published")
    public ResponseEntity<ApiResponse<List<JobPostingDTO>>> getPublishedJobs() {
        List<JobPostingDTO> jobs = recruitmentService.getPublishedJobs();
        return ResponseEntity.ok(ApiResponse.success("Published jobs retrieved", jobs));
    }

    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobPostingDTO>> getJobPosting(@PathVariable UUID id) {
        JobPostingDTO posting = recruitmentService.getJobPostingById(id);
        return ResponseEntity.ok(ApiResponse.success("Job posting retrieved", posting));
    }

    @PostMapping("/jobs")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobPostingDTO>> createJobPosting(@RequestBody JobPosting jobPosting) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        JobPostingDTO created = recruitmentService.createJobPosting(jobPosting, email);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Job posting created", created));
    }

    @PutMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobPostingDTO>> updateJobPosting(@PathVariable UUID id, @RequestBody JobPosting jobPosting) {
        JobPostingDTO updated = recruitmentService.updateJobPosting(id, jobPosting);
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
    public ResponseEntity<ApiResponse<Page<JobApplicationDTO>>> getApplications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<JobApplicationDTO> apps = recruitmentService.getApplications(pageable);
        return ResponseEntity.ok(ApiResponse.success("Applications retrieved", apps));
    }

    @GetMapping("/jobs/{jobId}/applications")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<JobApplicationDTO>>> getApplicationsByJob(@PathVariable UUID jobId) {
        List<JobApplicationDTO> apps = recruitmentService.getApplicationsByJob(jobId);
        return ResponseEntity.ok(ApiResponse.success("Applications retrieved", apps));
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobApplicationDTO>> getApplication(@PathVariable UUID id) {
        JobApplicationDTO app = recruitmentService.getApplicationById(id);
        return ResponseEntity.ok(ApiResponse.success("Application retrieved", app));
    }

    @PostMapping("/jobs/{jobId}/apply")
    public ResponseEntity<ApiResponse<JobApplicationDTO>> submitApplication(
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

        JobApplicationDTO submitted = recruitmentService.submitApplication(jobId, application, resume);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Application submitted", submitted));
    }

    @PatchMapping("/applications/{id}/status")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobApplicationDTO>> updateApplicationStatus(
            @PathVariable UUID id,
            @RequestParam JobApplication.ApplicationStatus status,
            @RequestParam(required = false) String notes) {
        JobApplicationDTO updated = recruitmentService.updateApplicationStatus(id, status, notes);
        return ResponseEntity.ok(ApiResponse.success("Application status updated", updated));
    }

    @PostMapping("/applications/{id}/schedule-interview")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<JobApplicationDTO>> scheduleInterview(
            @PathVariable UUID id,
            @RequestParam LocalDateTime interviewDate,
            @RequestParam(required = false) String notes) {
        JobApplicationDTO updated = recruitmentService.scheduleInterview(id, interviewDate, notes);
        return ResponseEntity.ok(ApiResponse.success("Interview scheduled", updated));
    }

    @PostMapping("/applications/{id}/convert-to-employee")
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeDetailsDTO>> convertToEmployee(@PathVariable UUID id) {
        EmployeeDetailsDTO employeeDto = recruitmentService.convertToEmployee(id);
        return ResponseEntity.ok(ApiResponse.success("Converted to employee", employeeDto));
    }
}