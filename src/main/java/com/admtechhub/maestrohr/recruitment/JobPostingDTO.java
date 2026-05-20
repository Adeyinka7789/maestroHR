package com.admtechhub.maestrohr.recruitment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobPostingDTO {
    private UUID id;
    private String title;
    private String department;
    private String location;
    private JobPosting.EmploymentType employmentType;
    private Long salaryRangeMin;
    private Long salaryRangeMax;
    private String description;
    private String requirements;
    private String benefits;
    private JobPosting.JobStatus status;
    private LocalDate postedDate;
    private LocalDate closingDate;
    private String createdBy;
    private LocalDateTime createdAt;
    private int applicationCount;  // number of applications received
}