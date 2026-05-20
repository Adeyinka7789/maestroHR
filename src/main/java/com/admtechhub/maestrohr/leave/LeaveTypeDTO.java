package com.admtechhub.maestrohr.leave;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveTypeDTO {
    private UUID id;
    private String name;
    private String code;
    private Integer maxDaysPerYear;
    private Boolean isPaid;
    private Boolean requiresApproval;
    private Boolean carryOverAllowed;
    private Integer maxCarryOverDays;
}