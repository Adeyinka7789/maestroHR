package com.admtechhub.maestrohr.disbursement;

import java.util.List;
import java.util.UUID;

public interface DisbursementProvider {

    /**
     * Initiate bulk transfer for salary payments
     * @param payments List of salary payments to process
     * @return DisbursementResult containing status and references
     */
    DisbursementResult initiateBulkTransfer(List<SalaryPayment> payments);

    /**
     * Check status of a transfer
     * @param reference The transfer reference
     * @return Current status of the transfer
     */
    DisbursementStatus checkStatus(String reference);

    /**
     * Verify a bank account
     * @param accountNumber Bank account number
     * @param bankCode Bank code
     * @return Account verification result
     */
    AccountVerificationResult verifyAccount(String accountNumber, String bankCode);

    /**
     * Get provider name
     * @return Provider identifier (e.g., "PAYSTACK", "FLUTTERWAVE", "CSV")
     */
    String getProviderName();

    /**
     * Check if provider is configured for the tenant
     * @param tenantId Tenant ID
     * @return true if configured
     */
    boolean isConfigured(UUID tenantId);
}