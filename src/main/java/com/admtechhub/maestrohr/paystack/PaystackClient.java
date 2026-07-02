package com.admtechhub.maestrohr.paystack;

import com.admtechhub.maestrohr.config.PaystackConfig;
import com.admtechhub.maestrohr.paystack.dto.PaystackRequest;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaystackClient {

    private final PaystackConfig paystackConfig;
    private final RestTemplate restTemplate;

    /**
     * Initialize a transaction. Safe to use Circuit Breaker,
     * but we avoid automated retries here to prevent double reference generation.
     */
    @CircuitBreaker(name = "paystack")
    public PaystackResponse.Data initializeTransaction(String email, Long amountKobo,
                                                       String reference, String callbackUrl) {
        String url = paystackConfig.getBaseUrl() + "/transaction/initialize";

        PaystackRequest.InitializeTransactionRequest request = PaystackRequest.InitializeTransactionRequest.builder()
                .email(email)
                .amount(amountKobo)
                .reference(reference)
                .currency("NGN")
                .callback_url(callbackUrl)
                .build();

        HttpEntity<PaystackRequest.InitializeTransactionRequest> entity = new HttpEntity<>(request, createHeaders());

        try {
            ResponseEntity<PaystackResponse> response = restTemplate.exchange(url, HttpMethod.POST, entity, PaystackResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody().getData();
            }
            throw new PaystackApiException("Paystack rejected initialization: " +
                    (response.getBody() != null ? response.getBody().getMessage() : "No message"));
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new PaystackApiException("HTTP API failure during transaction initialization", e);
        } catch (ResourceAccessException e) {
            throw new PaystackNetworkException("Network timeout initializing transaction", e);
        }
    }

    /**
     * Resolve account details. This is an idempotent read operation.
     * Completely safe for both Circuit Breaker and Auto-Retry patterns.
     */
    @CircuitBreaker(name = "paystack")
    @Retry(name = "paystackRead")
    public PaystackResponse.ResolveAccountData resolveAccount(String accountNumber, String bankCode) {
        String url = paystackConfig.getBaseUrl() + "/bank/resolve?account_number=" + accountNumber + "&bank_code=" + bankCode;

        try {
            ResponseEntity<PaystackResponse> response = restTemplate.exchange(url, HttpMethod.GET, createHttpEntity(), PaystackResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody().getResolveAccountData();
            }
            throw new PaystackApiException("Failed to resolve account payload");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new PaystackApiException("API Account verification rejected", e);
        } catch (ResourceAccessException e) {
            throw new PaystackNetworkException("Network drop during account lookup", e);
        }
    }

    /**
     * Create a transfer recipient. No automated retry to prevent duplicate recipients.
     */
    @CircuitBreaker(name = "paystack")
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

        try {
            ResponseEntity<PaystackResponse> response = restTemplate.exchange(url, HttpMethod.POST, entity, PaystackResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody().getData().getRecipientCode();
            }
            throw new PaystackApiException("Failed to create transfer recipient");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new PaystackApiException("API failure creating recipient", e);
        } catch (ResourceAccessException e) {
            throw new PaystackNetworkException("Network timeout creating recipient", e);
        }
    }

    /**
     * Initiate bulk transfer.
     * NOTICE: NO @Retry annotation. A network exception here leaves the system in an
     * UNKNOWN state. The caller must run a verification check rather than resending.
     */
    @CircuitBreaker(name = "paystack")
    public PaystackResponse initiateBulkTransfer(List<PaystackRequest.Transfer> transfers) {
        String url = paystackConfig.getBaseUrl() + "/bulk_transfer";

        PaystackRequest.BulkTransferRequest request = PaystackRequest.BulkTransferRequest.builder()
                .transfers(transfers)
                .source("balance")
                .build();

        HttpEntity<PaystackRequest.BulkTransferRequest> entity = new HttpEntity<>(request, createHeaders());

        try {
            ResponseEntity<PaystackResponse> response = restTemplate.exchange(url, HttpMethod.POST, entity, PaystackResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody();
            }
            throw new PaystackApiException("Bulk transfer execution refused by gateway");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new PaystackApiException("Paystack server error processing payouts", e);
        } catch (ResourceAccessException e) {
            log.error("CRITICAL: Socket timeout during live bulk transfer. Manual verification required via batch reference status lookup.");
            throw new PaystackUnknownStateException("Timeout during transmission. Payout status unknown.", e);
        }
    }

    /**
     * Verify the status of a bulk transfer by its batch reference.
     * This is the RECOVERY method for PaystackUnknownStateException scenarios.
     */
    @CircuitBreaker(name = "paystack")
    @Retry(name = "paystackRead")
    public PaystackResponse verifyBulkTransfer(String batchReference) {
        String url = paystackConfig.getBaseUrl() + "/bulk_transfer/" + batchReference;
        log.info("Verifying bulk transfer status for batch reference: {}", batchReference);

        try {
            ResponseEntity<PaystackResponse> response = restTemplate.exchange(url, HttpMethod.GET, createHttpEntity(), PaystackResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody();
            }
            throw new PaystackApiException("Failed to verify bulk transfer status for reference: " + batchReference);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new PaystackApiException("Bulk transfer not found with reference: " + batchReference, e);
            }
            throw new PaystackApiException("Client error verifying bulk transfer: " + batchReference, e);
        } catch (HttpServerErrorException e) {
            throw new PaystackApiException("Server error verifying bulk transfer: " + batchReference, e);
        } catch (ResourceAccessException e) {
            throw new PaystackNetworkException("Network timeout verifying bulk transfer", e);
        }
    }

    /**
     * Verify the status of a single transfer within a bulk batch by its transfer reference.
     */
    @CircuitBreaker(name = "paystack")
    @Retry(name = "paystackRead")
    public PaystackResponse verifyTransfer(String transferReference) {
        String url = paystackConfig.getBaseUrl() + "/transfer/" + transferReference;
        log.info("Verifying transfer status for reference: {}", transferReference);

        try {
            ResponseEntity<PaystackResponse> response = restTemplate.exchange(url, HttpMethod.GET, createHttpEntity(), PaystackResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody();
            }
            throw new PaystackApiException("Failed to verify transfer status for reference: " + transferReference);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new PaystackApiException("Transfer not found with reference: " + transferReference, e);
            }
            throw new PaystackApiException("Client error verifying transfer: " + transferReference, e);
        } catch (HttpServerErrorException e) {
            throw new PaystackApiException("Server error verifying transfer: " + transferReference, e);
        } catch (ResourceAccessException e) {
            throw new PaystackNetworkException("Network timeout verifying transfer", e);
        }
    }

    /**
     * Fetch list of banks. Safe read with a graceful degradation fallback.
     */
    @CircuitBreaker(name = "paystack", fallbackMethod = "getBanksFallback")
    @Retry(name = "paystackRead")
    public List<PaystackBank> getBanks() {
        String url = paystackConfig.getBaseUrl() + "/bank?currency=NGN";
        try {
            ResponseEntity<BankResponse> response = restTemplate.exchange(url, HttpMethod.GET, createHttpEntity(), BankResponse.class);
            if (response.getBody() != null && response.getBody().isStatus()) {
                return response.getBody().getData();
            }
            throw new PaystackApiException("Failed to map banks");
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new PaystackApiException("API failure retrieving banks list", e);
        } catch (ResourceAccessException e) {
            throw new PaystackNetworkException("Network timeout mapping bank directory", e);
        }
    }

    /**
     * Fallback method for getBanks - returns empty list when Paystack is down/circuit is open
     */
    public List<PaystackBank> getBanksFallback(Exception e) {
        log.warn("Returning empty bank list due to Paystack unavailability or Circuit Open state. Error: {}", e.getMessage());
        return Collections.emptyList();
    }

    private HttpEntity<Void> createHttpEntity() {
        return new HttpEntity<>(createHeaders());
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(paystackConfig.getSecretKey());
        return headers;
    }

    // --- Domain Specific Runtime Exceptions ---
    public static class PaystackException extends RuntimeException {
        public PaystackException(String m) { super(m); }
        public PaystackException(String m, Throwable c) { super(m, c); }
    }

    public static class PaystackApiException extends PaystackException {
        public PaystackApiException(String m) { super(m); }
        public PaystackApiException(String m, Throwable c) { super(m, c); }
    }

    public static class PaystackNetworkException extends PaystackException {
        public PaystackNetworkException(String m) { super(m); }
        public PaystackNetworkException(String m, Throwable c) { super(m, c); }
    }

    public static class PaystackUnknownStateException extends PaystackException {
        public PaystackUnknownStateException(String m, Throwable c) { super(m, c); }
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