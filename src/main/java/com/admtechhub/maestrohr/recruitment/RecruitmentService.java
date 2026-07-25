package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.*;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecruitmentService {

    private final JobPostingRepository jobPostingRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobApplicationResumeRepository jobApplicationResumeRepository;
    private final TenantRepository tenantRepository;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final CareersService careersService;

    /** Resume upload ceiling for the internal (HR-entered) apply path; mirrors the public one. */
    private static final long MAX_RESUME_BYTES = 5L * 1024 * 1024;

    // Helper to convert OffsetDateTime to LocalDateTime
    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        return odt != null ? odt.toLocalDateTime() : null;
    }

    // ==================== DTO CONVERSIONS ====================

    private JobPostingDTO toDto(JobPosting posting) {
        long appCount = jobApplicationRepository.countByJobPostingId(posting.getId());
        return JobPostingDTO.builder()
                .id(posting.getId())
                .title(posting.getTitle())
                .department(posting.getDepartment())
                .location(posting.getLocation())
                .employmentType(posting.getEmploymentType())
                .salaryRangeMin(posting.getSalaryRangeMin())
                .salaryRangeMax(posting.getSalaryRangeMax())
                .description(posting.getDescription())
                .requirements(posting.getRequirements())
                .benefits(posting.getBenefits())
                .status(posting.getStatus())
                .postedDate(posting.getPostedDate())
                .closingDate(posting.getClosingDate())
                .createdBy(posting.getCreatedBy())
                .createdAt(toLocalDateTime(posting.getCreatedAt()))
                .applicationCount((int) appCount)
                .build();
    }

    private JobApplicationDTO toDto(JobApplication app) {
        return JobApplicationDTO.builder()
                .id(app.getId())
                .jobPostingId(app.getJobPosting().getId())
                .jobTitle(app.getJobPosting().getTitle())
                .applicantName(app.getApplicantName())
                .applicantEmail(app.getApplicantEmail())
                .applicantPhone(app.getApplicantPhone())
                .resumeUrl(app.getResumeUrl())
                .hasResume(app.getResumeUrl() != null)
                .coverLetter(app.getCoverLetter())
                .status(app.getStatus())
                .source(app.getSource())
                .notes(app.getNotes())
                .interviewDate(app.getInterviewDate())
                .rating(app.getRating())
                .convertedToEmployeeId(app.getConvertedToEmployeeId())
                .createdAt(toLocalDateTime(app.getCreatedAt()))
                .build();
    }

    // ==================== JOB POSTING METHODS ====================

    @Transactional
    public JobPostingDTO createJobPosting(JobPosting jobPosting, String createdBy) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        jobPosting.setTenant(tenant);
        jobPosting.setCreatedBy(createdBy);

        if (jobPosting.getStatus() == JobPosting.JobStatus.PUBLISHED) {
            jobPosting.setPostedDate(LocalDate.now());
        }

        JobPosting saved = jobPostingRepository.save(jobPosting);
        return toDto(saved);
    }

    @Transactional
    public JobPostingDTO updateJobPosting(UUID id, JobPosting updatedJob) {
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

        JobPosting saved = jobPostingRepository.save(existing);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<JobPostingDTO> getJobPostings(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        Page<JobPosting> page = jobPostingRepository.findByTenantId(tenantId, pageable);
        return page.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<JobPostingDTO> getPublishedJobs() {
        UUID tenantId = getCurrentTenantId();
        List<JobPosting> postings = jobPostingRepository.findPublishedJobs(tenantId);
        return postings.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JobPostingDTO getJobPostingById(UUID id) {
        JobPosting posting = jobPostingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job posting not found"));
        return toDto(posting);
    }

    @Transactional
    public void deleteJobPosting(UUID id) {
        jobPostingRepository.deleteById(id);
    }

    // ==================== JOB APPLICATION METHODS ====================

    @Transactional
    public JobApplicationDTO submitApplication(UUID jobPostingId, JobApplication application, MultipartFile resume) {
        UUID tenantId = getCurrentTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        JobPosting jobPosting = jobPostingRepository.findById(jobPostingId)
                .orElseThrow(() -> new IllegalArgumentException("Job posting not found"));

        application.setTenant(tenant);
        application.setJobPosting(jobPosting);
        application.setStatus(JobApplication.ApplicationStatus.NEW);
        if (application.getSource() == null) {
            application.setSource(JobApplication.ApplicationSource.WEBSITE);
        }
        JobApplication saved = jobApplicationRepository.save(application);

        // Resume bytes now live in-row (job_application_resumes, V60) rather than on local disk,
        // so they survive restarts, respect tenant RLS, and match the public apply path.
        if (resume != null && !resume.isEmpty()) {
            storeResume(tenantId, saved.getId(), resume);
            saved.setResumeUrl("/api/recruitment/applications/" + saved.getId() + "/resume");
            saved = jobApplicationRepository.save(saved);
        }
        return toDto(saved);
    }

    /** Full resume entity (including BYTEA) for the authenticated HR download endpoint. */
    @Transactional(readOnly = true)
    public JobApplicationResume getResume(UUID applicationId) {
        return jobApplicationResumeRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("No resume on file for this application"));
    }

    @Transactional(readOnly = true)
    public Page<JobApplicationDTO> getApplications(Pageable pageable) {
        UUID tenantId = getCurrentTenantId();
        Page<JobApplication> page = jobApplicationRepository.findByTenantId(tenantId, pageable);
        return page.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<JobApplicationDTO> getApplicationsByJob(UUID jobPostingId) {
        List<JobApplication> apps = jobApplicationRepository.findByJobPostingId(jobPostingId);
        return apps.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JobApplicationDTO getApplicationById(UUID id) {
        JobApplication app = jobApplicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));
        return toDto(app);
    }

    @Transactional
    public JobApplicationDTO updateApplicationStatus(UUID id, JobApplication.ApplicationStatus status, String notes) {
        JobApplication application = jobApplicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        application.setStatus(status);
        if (notes != null) {
            application.setNotes(notes);
        }

        JobApplication saved = jobApplicationRepository.save(application);
        return toDto(saved);
    }

    @Transactional
    public JobApplicationDTO scheduleInterview(UUID id, LocalDateTime interviewDate, String notes) {
        JobApplication application = jobApplicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        application.setInterviewDate(interviewDate);
        application.setStatus(JobApplication.ApplicationStatus.INTERVIEW_SCHEDULED);
        if (notes != null) {
            application.setNotes(notes);
        }

        JobApplication saved = jobApplicationRepository.save(application);
        return toDto(saved);
    }

    @Transactional
    public EmployeeDetailsDTO convertToEmployee(UUID applicationId) {
        JobApplication application = jobApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        if (application.getStatus() != JobApplication.ApplicationStatus.HIRED) {
            throw new IllegalStateException("Application must be marked as HIRED before converting to employee");
        }

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

        EmployeeDetailsDTO employeeDto = employeeService.createEmployee(request);

        application.setConvertedToEmployeeId(employeeDto.getId().toString());
        jobApplicationRepository.save(application);

        log.info("Converted application {} to employee {}", applicationId, employeeDto.getId());
        return employeeDto;
    }

    /** Persist the uploaded resume for an application as an in-row BYTEA (tenant-scoped via RLS). */
    private void storeResume(UUID tenantId, UUID applicationId, MultipartFile file) {
        if (file.getSize() > MAX_RESUME_BYTES) {
            throw new IllegalArgumentException("Resume exceeds the 5 MB limit.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded resume.", e);
        }
        JobApplicationResume resume = JobApplicationResume.builder()
                .tenantId(tenantId)
                .applicationId(applicationId)
                .fileName(sanitizeFileName(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(bytes.length)
                .data(bytes)
                .build();
        jobApplicationResumeRepository.save(resume);
    }

    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "resume";
        }
        String name = original.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        return name.isEmpty() ? "resume" : name;
    }

    // ==================== CAREERS PAGE SETTINGS (HR-facing) ====================

    /** Current public careers-page settings for the caller's tenant (slug, on/off, link, tagline). */
    @Transactional(readOnly = true)
    public CareersSettingsDTO getCareersSettings() {
        Tenant tenant = tenantRepository.findById(getCurrentTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        return toCareersSettings(tenant);
    }

    /** Enable/disable the public careers page and/or update its tagline. */
    @Transactional
    public CareersSettingsDTO updateCareersSettings(Boolean enabled, String intro) {
        Tenant tenant = tenantRepository.findById(getCurrentTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        if (enabled != null) {
            tenant.setCareersEnabled(enabled);
        }
        if (intro != null) {
            tenant.setCareersIntro(intro.isBlank() ? null : intro.trim());
        }
        Tenant saved = tenantRepository.save(tenant);
        return toCareersSettings(saved);
    }

    private CareersSettingsDTO toCareersSettings(Tenant tenant) {
        return CareersSettingsDTO.builder()
                .slug(tenant.getCareersSlug())
                .enabled(tenant.isCareersEnabled())
                .intro(tenant.getCareersIntro())
                .publicUrl(careersService.publicUrlForSlug(tenant.getCareersSlug()))
                .build();
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