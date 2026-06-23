package com.admtechhub.maestrohr.attendance.device;

import lombok.Data;

import java.time.LocalDateTime;

/** Payload pushed by a biometric device for a single attendance event. */
@Data
public class DeviceAttendanceEventRequest {
    private String employeeIdentifier;
    private DeviceEventType eventType;
    private LocalDateTime timestamp;
    private String deviceId;
}
