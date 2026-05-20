package com.admtechhub.maestrohr.recruitment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobApplicationDTO {
    private UUID id;
    private UUID jobPostingId;
    private String jobTitle;
    private String applicantName;
    private String applicantEmail;
    private String applicantPhone;
    private String resumeUrl;
    private String coverLetter;
    private JobApplication.ApplicationStatus status;
    private JobApplication.ApplicationSource source;
    private String notes;
    private LocalDateTime interviewDate;
    private Integer rating;
    private String convertedToEmployeeId;
    private LocalDateTime createdAt;
}