package com.admtechhub.maestrohr.disbursement;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DisbursementResult {
    private boolean success;
    private String batchReference;
    private List<TransferResult> transfers;
    private String message;

    @Data
    @Builder
    public static class TransferResult {
        private String employeeId;
        private String reference;
        private String status;
        private String errorMessage;
    }
}