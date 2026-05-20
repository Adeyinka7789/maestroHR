package com.admtechhub.maestrohr.exit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExitRequestDTO {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private String employeeNumber;
    private String exitType;
    private LocalDate lastWorkingDay;
    private String reason;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private Integer clearanceProgress;      // computed: % of cleared items
    private String settlementStatus;        // from FinalSettlement (or PENDING)
    private List<EmployeeClearanceDTO> clearanceItems; // detailed clearance
    private FinalSettlementDTO settlement;   // detailed settlement
}