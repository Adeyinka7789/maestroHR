package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.notification.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public careers portal logic: resolve a tenant from its public slug, expose its PUBLISHED jobs to
 * unauthenticated candidates, and accept applications. All persistence flows through the privileged
 * {@link CareersPublicRepository} because the caller has no tenant session (see that class).
 *
 * <p>This is the public counterpart of {@link RecruitmentService} (the authenticated, tenant-scoped
 * ATS). The two are kept separate on purpose — same rationale as the {@code *Service} /
 * {@code *ListService} split documented in CLAUDE.md: the public path has different auth, different
 * data-access, and stricter output than the internal one.
 */
@Service
@Slf4j
public class CareersService {

    /** Resume upload constraints for the public form. Modest because bytes live in the row. */
    static final long MAX_RESUME_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final Set<String> ALLOWED_RESUME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** A single email may submit at most this many applications per posting within the window. */
    private static final int MAX_APPLICATIONS_PER_EMAIL = 3;
    private static final java.time.Duration DEDUP_WINDOW = java.time.Duration.ofDays(1);

    private final CareersPublicRepository repository;
    private final ObjectProvider<EmailService> emailService;

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    public CareersService(CareersPublicRepository repository, ObjectProvider<EmailService> emailService) {
        this.repository = repository;
        this.emailService = emailService;
    }

    /** Result of a resume validation attempt. */
    public record ResumeError(String message) {
    }

    /** Thrown when a slug resolves to no live, careers-enabled company (rendered as "unavailable"). */
    public static class CareersUnavailableException extends RuntimeException {
        public CareersUnavailableException(String message) {
            super(message);
        }
    }

    /** Thrown when the requested posting is not a PUBLISHED job of the resolved company (404). */
    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String message) {
            super(message);
        }
    }

    /** Thrown when the submitted application fails validation (bad resume, dedup, etc.). */
    public static class ApplicationRejectedException extends RuntimeException {
        public ApplicationRejectedException(String message) {
            super(message);
        }
    }

    // ── reads ────────────────────────────────────────────────────────────────────

    /** Landing page: the company plus its published jobs. Throws if the page is unavailable. */
    public CareersView.Listing loadListing(String slug) {
        CareersView.Company company = resolveCompany(slug);
        List<CareersView.Job> jobs = repository.findPublishedJobs(company.id());
        return new CareersView.Listing(company, jobs);
    }

    /** Company + a single published job for the detail/apply page. Throws if unavailable / not found. */
    public JobDetail loadJob(String slug, UUID jobId) {
        CareersView.Company company = resolveCompany(slug);
        CareersView.Job job = repository.findPublishedJob(company.id(), jobId)
                .orElseThrow(() -> new JobNotFoundException("This position is no longer accepting applications."));
        return new JobDetail(company, job);
    }

    public record JobDetail(CareersView.Company company, CareersView.Job job) {
    }

    private CareersView.Company resolveCompany(String slug) {
        if (!repository.recruitmentEnabled()) {
            throw new CareersUnavailableException("Careers pages are temporarily unavailable.");
        }
        CareersView.Company company = repository.findCompanyBySlug(slug)
                .orElseThrow(() -> new CareersUnavailableException("This careers page could not be found."));
        if (!company.careersEnabled()) {
            throw new CareersUnavailableException("This company is not accepting applications right now.");
        }
        return company;
    }

    // ── apply ────────────────────────────────────────────────────────────────────

    /**
     * Validate and persist a public application. Returns the new application id. Callers handle the
     * honeypot before calling this (a tripped honeypot should look like success without persisting).
     *
     * @throws CareersUnavailableException  slug no longer resolves to a live careers page
     * @throws JobNotFoundException         posting is not a published job of this company
     * @throws ApplicationRejectedException resume invalid, or per-email submission cap exceeded
     */
    public UUID submitApplication(String slug, UUID jobId,
                                  String applicantName, String applicantEmail, String applicantPhone,
                                  String coverLetter, MultipartFile resume) {
        JobDetail detail = loadJob(slug, jobId);
        UUID tenantId = detail.company().id();

        requireText(applicantName, "Please enter your full name.");
        requireText(applicantEmail, "Please enter your email address.");
        if (!applicantEmail.contains("@")) {
            throw new ApplicationRejectedException("Please enter a valid email address.");
        }

        int recent = repository.countRecentApplications(
                tenantId, jobId, applicantEmail, OffsetDateTime.now().minus(DEDUP_WINDOW));
        if (recent >= MAX_APPLICATIONS_PER_EMAIL) {
            throw new ApplicationRejectedException(
                    "You have already applied for this position. We have your application on file.");
        }

        byte[] resumeBytes = null;
        String resumeName = null;
        String resumeType = null;
        if (resume != null && !resume.isEmpty()) {
            validateResume(resume);
            try {
                resumeBytes = resume.getBytes();
            } catch (IOException e) {
                throw new ApplicationRejectedException("We could not read your resume file. Please try again.");
            }
            resumeName = sanitizeFileName(resume.getOriginalFilename());
            resumeType = resume.getContentType();
        }

        UUID applicationId = repository.insertApplication(
                tenantId, jobId,
                applicantName.trim(), applicantEmail.trim(), trimOrNull(applicantPhone),
                trimOrNull(coverLetter),
                resumeBytes, resumeName, resumeType);

        notifyBestEffort(detail, applicantName.trim(), applicantEmail.trim());
        log.info("Public application {} received for job {} (tenant {})", applicationId, jobId, tenantId);
        return applicationId;
    }

    private void validateResume(MultipartFile resume) {
        if (resume.getSize() > MAX_RESUME_BYTES) {
            throw new ApplicationRejectedException("Your resume exceeds the 5 MB limit.");
        }
        String type = resume.getContentType();
        if (type == null || !ALLOWED_RESUME_TYPES.contains(type)) {
            throw new ApplicationRejectedException("Please upload your resume as a PDF or Word document.");
        }
    }

    /** Fire-and-forget candidate confirmation + HR notification. Never blocks the application. */
    private void notifyBestEffort(JobDetail detail, String applicantName, String applicantEmail) {
        EmailService email = emailService.getIfAvailable();
        if (email == null) {
            return; // mail not configured (e.g. tests) — applications still persist
        }
        String company = detail.company().companyName();
        String jobTitle = detail.job().title();
        try {
            Map<String, Object> candidateVars = new HashMap<>();
            candidateVars.put("applicantName", applicantName);
            candidateVars.put("companyName", company);
            candidateVars.put("jobTitle", jobTitle);
            email.sendTemplatedEmail(applicantEmail,
                    "Application received – " + jobTitle,
                    "email/application-received", candidateVars);
        } catch (RuntimeException e) {
            log.warn("Candidate confirmation email failed for {}: {}", applicantEmail, e.getMessage());
        }
        try {
            List<String> recipients = repository.findNotificationRecipients(detail.company().id());
            for (String hr : recipients) {
                Map<String, Object> hrVars = new HashMap<>();
                hrVars.put("applicantName", applicantName);
                hrVars.put("applicantEmail", applicantEmail);
                hrVars.put("jobTitle", jobTitle);
                hrVars.put("dashboardUrl", appUrl + "/recruitment");
                email.sendTemplatedEmail(hr,
                        "New application – " + jobTitle,
                        "email/new-application", hrVars);
            }
        } catch (RuntimeException e) {
            log.warn("HR application notification failed for tenant {}: {}", detail.company().id(), e.getMessage());
        }
    }

    /** The public URL for a tenant's careers page, for display in the HR settings screen. */
    public String publicUrlForSlug(String slug) {
        return slug == null ? null : appUrl + "/careers/" + slug;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApplicationRejectedException(message);
        }
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Strip any path components a client may have smuggled into the original filename (as V34). */
    private static String sanitizeFileName(String original) {
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

    /** Exposed for the resume-validation unit reuse; keeps the allowed set in one place. */
    public Optional<ResumeError> checkResume(MultipartFile resume) {
        try {
            if (resume != null && !resume.isEmpty()) {
                validateResume(resume);
            }
            return Optional.empty();
        } catch (ApplicationRejectedException e) {
            return Optional.of(new ResumeError(e.getMessage()));
        }
    }
}
