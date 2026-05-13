package com.admtechhub.maestrohr.paystack.dto;

import lombok.Builder;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@Data
@Builder
public class PaystackResponse {
    private boolean status;
    private String message;
    private Data data;

    @lombok.Data
    @Builder
    public static class Data {
        // For transfer recipient creation
        private String recipientCode;
        private String transferCode;
        private String reference;
        private String status;
        private Integer totalAmount;

        @JsonProperty("transfer_codes")
        private List<String> transferCodes;

        private List<TransferData> transfers;

        // For bank account resolution
        @JsonProperty("account_number")
        private String accountNumber;

        @JsonProperty("account_name")
        private String accountName;

        @JsonProperty("bank_code")
        private String bankCode;

        @JsonProperty("bank_name")
        private String bankName;
    }

    @lombok.Data
    @Builder
    public static class TransferData {
        private Integer amount;
        private String reference;
        private String recipient;
        private String status;
        private String transferCode;
    }

    // Helper method to get resolve account data
    public ResolveAccountData getResolveAccountData() {
        if (data == null) return null;
        return ResolveAccountData.builder()
                .accountNumber(data.getAccountNumber())
                .accountName(data.getAccountName())
                .bankCode(data.getBankCode())
                .bankName(data.getBankName())
                .build();
    }

    @lombok.Data
    @Builder
    public static class ResolveAccountData {
        private String accountNumber;
        private String accountName;
        private String bankCode;
        private String bankName;
    }
}