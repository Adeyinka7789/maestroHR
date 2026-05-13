package com.admtechhub.maestrohr.paystack.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class PaystackRequest {

    @Data
    @Builder
    public static class ResolveAccountRequest {
        private String accountNumber;
        private String bankCode;
    }

    @Data
    @Builder
    public static class TransferRecipientRequest {
        @Builder.Default
        private String type = "nuban";
        private String name;
        private String accountNumber;
        private String bankCode;
        @Builder.Default
        private String currency = "NGN";
        private String description;
    }

    @Data
    @Builder
    public static class BulkTransferRequest {
        private List<Transfer> transfers;
        @Builder.Default
        private String source = "balance";
    }

    @Data
    @Builder
    public static class Transfer {
        private Integer amount; // in kobo
        private String recipient;
        private String reference;
        private String reason;
    }
}