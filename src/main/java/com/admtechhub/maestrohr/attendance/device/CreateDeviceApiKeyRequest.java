package com.admtechhub.maestrohr.attendance.device;

import lombok.Data;

@Data
public class CreateDeviceApiKeyRequest {
    private String deviceName;
    private String deviceIdentifier;
    private String location;
}
