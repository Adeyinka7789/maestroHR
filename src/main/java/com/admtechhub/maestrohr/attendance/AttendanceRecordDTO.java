package com.admtechhub.maestrohr.attendance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceRecordDTO {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private LocalDate attendanceDate;
    private LocalTime clockInTime;
    private LocalTime clockOutTime;
    private BigDecimal hoursWorked;
    private AttendanceStatus status;
    private String checkInMethod;
    private String notes;
}