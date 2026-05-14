package com.admtechhub.maestrohr.disbursement;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SalaryPayment {
    private String employeeId;
    private String employeeNumber;
    private String employeeName;
    private String accountNumber;
    private String bankCode;
    private String bankName;
    private String accountName;
    private Long amountKobo;  // Amount in kobo (1 NGN = 100 kobo)
    private String reference;
    private String narration;
}