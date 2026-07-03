package com.admtechhub.maestrohr.retirement;

import lombok.Data;

@Data
public class RetirementPolicyRequest {
    private Integer retirementAge;
    private String notificationThresholdDaysRaw;
}
