package com.admtechhub.maestrohr.leave;

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
public class LeaveRequestDTO {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private String employeeNumber;
    private UUID leaveTypeId;
    private String leaveTypeName;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysRequested;
    private String reason;
    private UUID coverOfficerId;
    private String coverOfficerName;
    private LeaveStatus status;
    private String rejectionReason;
    private String approvalComment;
    private LocalDateTime approvedAt;
    private String approvedByEmail;
    private LocalDateTime createdAt;
}