package com.admtechhub.maestrohr.attendance.device;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceApiKeyDTO {
    private UUID id;
    private String deviceName;
    private String deviceIdentifier;
    private String location;
    private boolean active;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime createdAt;
}
