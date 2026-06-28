package com.admtechhub.maestrohr.auth;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MeResponse {
    private String email;
    private String role;
    private boolean hasCompletedOnboarding;
    private String firstName;
    private String companyName;
    private String departmentName;
    /** Days left in free trial. Non-null only when subscription status is TRIALING. */
    private Integer daysRemainingInTrial;
}
