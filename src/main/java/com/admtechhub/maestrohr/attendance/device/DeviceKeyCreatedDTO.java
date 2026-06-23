package com.admtechhub.maestrohr.attendance.device;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Returned once on key creation. {@code rawKey} is never stored and cannot be retrieved again. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceKeyCreatedDTO {
    private UUID id;
    private String deviceName;
    private String rawKey;
    private OffsetDateTime createdAt;
}
