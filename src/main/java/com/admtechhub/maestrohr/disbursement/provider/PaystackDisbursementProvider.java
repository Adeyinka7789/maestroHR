package com.admtechhub.maestrohr.disbursement.provider;

import com.admtechhub.maestrohr.disbursement.*;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaystackDisbursementProvider implements DisbursementProvider {

    private final PaystackClient paystackClient;
    private final TenantRepository tenantRepository;

    @Override
    public DisbursementResult initiateBulkTransfer(List<SalaryPayment> payments) {
        log.info("Initiating Paystack bulk transfer for {} payments", payments.size());

        List<com.admtechhub.maestrohr.paystack.dto.PaystackRequest.Transfer> transfers = payments.stream()
                .map(payment -> com.admtechhub.maestrohr.paystack.dto.PaystackRequest.Transfer.builder()
                        .amount(payment.getAmountKobo().intValue())
                        .recipient(getOrCreateRecipientCode(payment))
                        .reference(payment.getReference())
                        .reason(payment.getNarration())
                        .build())
                .collect(Collectors.toList());

        try {
            var response = paystackClient.initiateBulkTransfer(transfers);

            List<DisbursementResult.TransferResult> transferResults = payments.stream()
                    .map(payment -> DisbursementResult.TransferResult.builder()
                            .employeeId(payment.getEmployeeId())
                            .reference(payment.getReference())
                            .status("PENDING")
                            .build())
                    .collect(Collectors.toList());

            return DisbursementResult.builder()
                    .success(true)
                    .batchReference(response.getData().getTransferCodes().toString())
                    .transfers(transferResults)
                    .message("Bulk transfer initiated successfully")
                    .build();

        } catch (Exception e) {
            log.error("Paystack bulk transfer failed: {}", e.getMessage());
            return DisbursementResult.builder()
                    .success(false)
                    .message("Failed to initiate transfer: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public DisbursementStatus checkStatus(String reference) {
        // Implement status check via Paystack API
        return DisbursementStatus.PENDING;
    }

    @Override
    public AccountVerificationResult verifyAccount(String accountNumber, String bankCode) {
        try {
            var result = paystackClient.resolveAccount(accountNumber, bankCode);
            return AccountVerificationResult.builder()
                    .verified(true)
                    .accountNumber(result.getAccountNumber())
                    .accountName(result.getAccountName())
                    .bankCode(result.getBankCode())
                    .bankName(result.getBankName())
                    .message("Account verified successfully")
                    .build();
        } catch (Exception e) {
            return AccountVerificationResult.builder()
                    .verified(false)
                    .message("Verification failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public String getProviderName() {
        return "PAYSTACK";
    }

    @Override
    public boolean isConfigured(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        return tenant != null && tenant.getPaystackCustomerCode() != null;
    }

    private String getOrCreateRecipientCode(SalaryPayment payment) {
        // Check if recipient exists, create if not
        return paystackClient.createTransferRecipient(
                payment.getAccountName(),
                payment.getAccountNumber(),
                payment.getBankCode()
        );
    }
}