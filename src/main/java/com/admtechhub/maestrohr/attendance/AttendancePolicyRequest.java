package com.admtechhub.maestrohr.attendance;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AttendancePolicyRequest {
    private String name;
    private String description;
    private Integer gracePeriodMinutes;
    private DeductionType lateDeductionType;
    private BigDecimal lateDeductionValue;
    private Integer lateFreeCount;
    private DeductionType absenceDeductionType;
    private BigDecimal absenceDeductionValue;
    private Boolean lateToAbsenceConversionEnabled;
    private Integer lateToAbsenceConversionCount;
    private Boolean isActive;
}
