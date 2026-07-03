package com.admtechhub.maestrohr.attendance;

import lombok.Data;

/** Start/end time arrive as "HH:mm" strings (mirrors {@code AttendanceService#parseTime}), parsed in the service. */
@Data
public class ShiftRequest {
    private String name;
    private String startTime;
    private String endTime;
    private Boolean isDefault;
}
