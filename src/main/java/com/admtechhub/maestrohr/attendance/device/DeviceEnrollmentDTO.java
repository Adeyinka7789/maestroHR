package com.admtechhub.maestrohr.attendance.device;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceEnrollmentDTO {
    private UUID id;
    private UUID deviceApiKeyId;
    private String deviceName;
    private String deviceEmployeeId;
    private UUID employeeId;
    private String employeeName;
    private OffsetDateTime enrolledAt;
}
