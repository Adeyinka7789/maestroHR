package com.admtechhub.maestrohr.attendance.device;

import lombok.Data;

import java.util.UUID;

@Data
public class CreateEnrollmentRequest {
    private UUID deviceApiKeyId;
    private String deviceEmployeeId;
    private UUID employeeId;
}
