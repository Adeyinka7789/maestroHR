package com.admtechhub.maestrohr.recruitment;

import com.admtechhub.maestrohr.common.BaseEntity;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_applications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class JobApplication extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @Column(name = "applicant_name", nullable = false)
    private String applicantName;

    @Column(name = "applicant_email", nullable = false)
    private String applicantEmail;

    @Column(name = "applicant_phone")
    private String applicantPhone;

    @Column(name = "resume_url")
    private String resumeUrl;

    @Column(name = "cover_letter", columnDefinition = "TEXT")
    private String coverLetter;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;

    @Column(name = "source")
    @Enumerated(EnumType.STRING)
    private ApplicationSource source;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "interview_date")
    private LocalDateTime interviewDate;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "converted_to_employee_id")
    private String convertedToEmployeeId;

    public enum ApplicationStatus {
        NEW, UNDER_REVIEW, INTERVIEW_SCHEDULED, OFFER_EXTENDED, HIRED, REJECTED, WITHDRAWN
    }

    public enum ApplicationSource {
        WEBSITE, LINKEDIN, INDEED, REFERRAL, EMAIL
    }
}