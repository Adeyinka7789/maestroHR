package com.admtechhub.maestrohr.paystack;

import com.admtechhub.maestrohr.config.PaystackConfig;
import com.admtechhub.maestrohr.paystack.dto.PaystackRequest;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaystackClient {

    private final PaystackConfig paystackConfig;
    private final RestTemplate restTemplate;

    /**
     * Resolve bank account to verify account name
     */
    public PaystackResponse.ResolveAccountData resolveAccount(String accountNumber, String bankCode) {
        String url = paystackConfig.getBaseUrl() + "/bank/resolve?account_number=" + accountNumber + "&bank_code=" + bankCode;

        HttpEntity<Void> entity = createHttpEntity();
        ResponseEntity<PaystackResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, PaystackResponse.class);

        if (response.getBody() != null && response.getBody().isStatus()) {
            return response.getBody().getResolveAccountData();
        }
        throw new RuntimeException("Failed to resolve account: " + (response.getBody() != null ? response.getBody().getMessage() : "Unknown error"));
    }
    /**
     * Create a transfer recipient
     */
    public String createTransferRecipient(String name, String accountNumber, String bankCode) {
        String url = paystackConfig.getBaseUrl() + "/transferrecipient";

        PaystackRequest.TransferRecipientRequest request = PaystackRequest.TransferRecipientRequest.builder()
                .type("nuban")
                .name(name)
                .accountNumber(accountNumber)
                .bankCode(bankCode)
                .currency("NGN")
                .description("Salary recipient")
                .build();

        HttpEntity<PaystackRequest.TransferRecipientRequest> entity = new HttpEntity<>(request, createHeaders());
        ResponseEntity<PaystackResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, PaystackResponse.class);

        if (response.getBody() != null && response.getBody().isStatus()) {
            return response.getBody().getData().getRecipientCode();
        }
        throw new RuntimeException("Failed to create recipient: " + response.getBody());
    }

    /**
     * Initiate bulk transfer
     */
    public PaystackResponse initiateBulkTransfer(List<PaystackRequest.Transfer> transfers) {
        String url = paystackConfig.getBaseUrl() + "/bulk_transfer";

        PaystackRequest.BulkTransferRequest request = PaystackRequest.BulkTransferRequest.builder()
                .transfers(transfers)
                .source("balance")
                .build();

        HttpEntity<PaystackRequest.BulkTransferRequest> entity = new HttpEntity<>(request, createHeaders());
        ResponseEntity<PaystackResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, PaystackResponse.class);

        if (response.getBody() != null && response.getBody().isStatus()) {
            return response.getBody();
        }
        throw new RuntimeException("Failed to initiate bulk transfer: " + response.getBody());
    }

    /**
     * Get list of banks
     */
    public List<PaystackBank> getBanks() {
        String url = paystackConfig.getBaseUrl() + "/bank?currency=NGN";
        HttpEntity<Void> entity = createHttpEntity();
        ResponseEntity<BankResponse> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, BankResponse.class);

        if (response.getBody() != null && response.getBody().isStatus()) {
            return response.getBody().getData();
        }
        throw new RuntimeException("Failed to fetch banks");
    }

    private HttpEntity<Void> createHttpEntity() {
        HttpHeaders headers = createHeaders();
        return new HttpEntity<>(headers);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(paystackConfig.getSecretKey());
        return headers;
    }

    @lombok.Data
    public static class BankResponse {
        private boolean status;
        private List<PaystackBank> data;
    }

    @lombok.Data
    public static class PaystackBank {
        private String code;
        private String name;
    }
}