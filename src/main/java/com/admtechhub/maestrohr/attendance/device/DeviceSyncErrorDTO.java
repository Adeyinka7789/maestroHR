package com.admtechhub.maestrohr.attendance.device;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceSyncErrorDTO {
    private UUID id;
    private String deviceName;
    private String deviceEmployeeId;
    private String deviceIdentifier;
    private String eventType;
    private OffsetDateTime eventTimestamp;
    private String errorReason;
    private boolean resolved;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime createdAt;
}
