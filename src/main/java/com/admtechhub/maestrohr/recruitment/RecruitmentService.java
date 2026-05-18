package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeRequest;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecruitmentService {

    private final JobPostingRepository jobPostingRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final TenantRepository tenantRepository;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;

    private static final String RESUME_UPLOAD_DIR = "uploads/resumes/";

    // ==================== JOB POSTING METHODS ====================

    @Transactional
    public JobPosting createJobPosting(JobPosting jobPosting, String createdBy) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        jobPosting.setTenant(tenant);
        jobPosting.setCreatedBy(createdBy);

        if (jobPosting.getStatus() == JobPosting.JobStatus.PUBLISHED) {
            jobPosting.setPostedDate(LocalDate.now());
        }

        return jobPostingRepository.save(jobPosting);
    }

    @Transactional
    public JobPosting updateJobPosting(UUID id, JobPosting updatedJob) {
        JobPosting existing = jobPostingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job posting not found"));

        existing.setTitle(updatedJob.getTitle());
        existing.setDepartment(updatedJob.getDepartment());
        existing.setLocation(updatedJob.getLocation());
        existing.setEmploymentType(updatedJob.getEmploymentType());
        existing.setSalaryRangeMin(updatedJob.getSalaryRangeMin());
        existing.setSalaryRangeMax(updatedJob.getSalaryRangeMax());
        existing.setDescription(updatedJob.getDescription());
        existing.setRequirements(updatedJob.getRequirements());
        existing.setBenefits(updatedJob.getBenefits());
        existing.setStatus(updatedJob.getStatus());
        existing.setClosingDate(updatedJob.getClosingDate());

        if (updatedJob.getStatus() == JobPosting.JobStatus.PUBLISHED && existing.getPostedDate() == null) {
            existing.setPostedDate(LocalDate.now());
        }

        return jobPostingRepository.save(existing);
    }

    @Transactional(readOnly = true)
    public Page<JobPosting> getJobPostings(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        return jobPostingRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public List<JobPosting> getPublishedJobs() {
        UUID tenantId = getCurrentTenantId();
        return jobPostingRepository.findPublishedJobs(tenantId);
    }

    @Transactional(readOnly = true)
    public JobPosting getJobPostingById(UUID id) {
        return jobPostingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job posting not found"));
    }

    @Transactional
    public void deleteJobPosting(UUID id) {
        jobPostingRepository.deleteById(id);
    }

    // ==================== JOB APPLICATION METHODS ====================

    @Transactional
    public JobApplication submitApplication(UUID jobPostingId, JobApplication application, MultipartFile resume) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        JobPosting jobPosting = jobPostingRepository.findById(jobPostingId)
                .orElseThrow(() -> new IllegalArgumentException("Job posting not found"));

        String resumeUrl = saveResume(resume);

        application.setTenant(tenant);
        application.setJobPosting(jobPosting);
        application.setResumeUrl(resumeUrl);
        application.setStatus(JobApplication.ApplicationStatus.NEW);
        application.setSource(JobApplication.ApplicationSource.WEBSITE);

        return jobApplicationRepository.save(application);
    }

    @Transactional(readOnly = true)
    public Page<JobApplication> getApplications(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        return jobApplicationRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public List<JobApplication> getApplicationsByJob(UUID jobPostingId) {
        return jobApplicationRepository.findByJobPostingId(jobPostingId);
    }

    @Transactional(readOnly = true)
    public JobApplication getApplicationById(UUID id) {
        return jobApplicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));
    }

    @Transactional
    public JobApplication updateApplicationStatus(UUID id, JobApplication.ApplicationStatus status, String notes) {
        JobApplication application = jobApplicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        application.setStatus(status);
        if (notes != null) {
            application.setNotes(notes);
        }

        return jobApplicationRepository.save(application);
    }

    @Transactional
    public JobApplication scheduleInterview(UUID id, LocalDateTime interviewDate, String notes) {
        JobApplication application = jobApplicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        application.setInterviewDate(interviewDate);
        application.setStatus(JobApplication.ApplicationStatus.INTERVIEW_SCHEDULED);
        if (notes != null) {
            application.setNotes(notes);
        }

        return jobApplicationRepository.save(application);
    }

    @Transactional
    public Employee convertToEmployee(UUID applicationId) {
        JobApplication application = jobApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        if (application.getStatus() != JobApplication.ApplicationStatus.HIRED) {
            throw new IllegalStateException("Application must be marked as HIRED before converting to employee");
        }

        // Create employee from application data
        EmployeeRequest request = new EmployeeRequest();
        String[] nameParts = application.getApplicantName().split(" ", 2);
        request.setFirstName(nameParts[0]);
        request.setLastName(nameParts.length > 1 ? nameParts[1] : "");
        request.setEmail(application.getApplicantEmail());
        request.setPhone(application.getApplicantPhone() != null ? application.getApplicantPhone() : "");
        request.setJobTitle("New Hire");
        request.setEmploymentType(com.admtechhub.maestrohr.employee.EmploymentType.FULL_TIME);
        request.setEmploymentStartDate(LocalDate.now());
        request.setPassword(generateRandomPassword());
        request.setDateOfBirth(LocalDate.of(1990, 1, 1));
        request.setGender(com.admtechhub.maestrohr.employee.Gender.MALE);
        request.setMaritalStatus(com.admtechhub.maestrohr.employee.MaritalStatus.SINGLE);
        request.setAddress("To be updated");
        request.setBankName("To be updated");
        request.setBankAccountNumber("0000000000");
        request.setBankAccountName(application.getApplicantName());

        // Get default department and pay grade
        // (You may need to set these based on the job posting)

        Employee employee = employeeService.createEmployee(request);

        application.setConvertedToEmployeeId(employee.getId().toString());
        jobApplicationRepository.save(application);

        log.info("Converted application {} to employee {}", applicationId, employee.getId());

        return employee;
    }

    private String saveResume(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(RESUME_UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String filename = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath);

            return "/uploads/resumes/" + filename;
        } catch (IOException e) {
            log.error("Failed to save resume: {}", e.getMessage());
            return null;
        }
    }

    private String generateRandomPassword() {
        return "Welcome" + UUID.randomUUID().toString().substring(0, 6) + "!";
    }

    private UUID getCurrentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantId);
    }
}