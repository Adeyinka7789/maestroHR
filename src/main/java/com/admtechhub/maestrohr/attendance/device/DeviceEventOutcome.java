package com.admtechhub.maestrohr.attendance.device;

import com.admtechhub.maestrohr.attendance.AttendanceRecordDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-event result returned by the bulk-sync endpoint so one bad record doesn't fail the batch. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceEventOutcome {

    public enum Status { ACCEPTED, DUPLICATE, EMPLOYEE_NOT_FOUND, ERROR }

    private String employeeIdentifier;
    private String eventType;
    private Status status;
    private String message;
    private AttendanceRecordDTO record;
}
