package com.admtechhub.maestrohr.tenant.dto;

import lombok.Data;

@Data
public class TenantSettingsRequest {
    private String disbursementProvider;  // PAYSTACK or CSV
    private Boolean autoRenew;
    private String companyName;
    private String industry;
    private String companySize;
}