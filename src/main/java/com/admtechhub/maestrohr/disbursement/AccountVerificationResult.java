package com.admtechhub.maestrohr.disbursement;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccountVerificationResult {
    private boolean verified;
    private String accountNumber;
    private String accountName;
    private String bankCode;
    private String bankName;
    private String message;
}