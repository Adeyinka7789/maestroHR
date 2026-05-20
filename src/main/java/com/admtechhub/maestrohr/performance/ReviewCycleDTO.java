package com.admtechhub.maestrohr.performance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewCycleDTO {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private String employeeNumber;
    private UUID reviewerId;
    private String reviewerName;
    private UUID templateId;
    private String templateName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate dueDate;
    private String status;
    private String selfReviewStatus;
    private String managerReviewStatus;
    private BigDecimal overallRating;
    private String createdBy;
    private LocalDateTime createdAt;
}