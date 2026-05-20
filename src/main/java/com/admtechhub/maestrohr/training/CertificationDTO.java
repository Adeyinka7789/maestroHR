package com.admtechhub.maestrohr.training;

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
public class CertificationDTO {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private String employeeNumber;
    private String name;
    private String issuingBody;
    private LocalDate issueDate;
    private LocalDate expiryDate;
    private String certificateUrl;
    private Boolean reminderSent;
    private String status;
    private LocalDateTime createdAt;
}