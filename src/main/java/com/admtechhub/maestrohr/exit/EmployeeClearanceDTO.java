package com.admtechhub.maestrohr.exit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeClearanceDTO {
    private UUID id;
    private UUID exitRequestId;
    private UUID clearanceItemId;
    private String clearanceItemName;
    private String clearanceItemDepartment;
    private String status;
    private String clearedBy;
    private LocalDateTime clearedAt;
    private String notes;
}