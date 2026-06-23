package com.admtechhub.maestrohr.attendance.device;

import lombok.Data;

import java.util.List;

/** Batch of offline attendance events pushed by a biometric device. */
@Data
public class DeviceBulkSyncRequest {
    private String deviceId;
    private List<DeviceAttendanceEventRequest> events;
}
