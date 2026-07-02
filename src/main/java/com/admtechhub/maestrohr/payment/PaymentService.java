package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final TenantRepository tenantRepository;
    private final PricingService pricingService;
    private final InvoiceRepository invoiceRepository;
    private final PaystackClient paystackClient;

    @Value("${paystack.transaction-callback-url:http://localhost:8080/dashboard?payment=success}")
    private String transactionCallbackUrl;

    /**
     * Entry method: Intentionally NOT annotated with global @Transactional.
     * This isolates database transactions from the Paystack API network call.
     */
    public Map<String, String> prepareAndInitializePayment(UUID tenantId, String billingEmail, String rawPlan, String rawPeriod) {
        SubscriptionPlan plan = SubscriptionPlan.valueOf(rawPlan.toUpperCase());
        PaymentPeriod period = PaymentPeriod.valueOf(rawPeriod.toUpperCase());

        long amountKobo = pricingService.getPrice(plan.name(), period.name());
        if (amountKobo <= 0) {
            throw new IllegalArgumentException("Plan " + plan + "/" + period + " is not payable");
        }

        // 1. Generate an un-hashed reference based on target details to allow safe idempotency retries.
        // If a user clicks 'Pay' twice, it will reuse the exact pending invoice record instead of creating duplicates.
        String reference = "SUB_" + tenantId.toString().substring(0, 8) + "_" + plan.name() + "_" + period.name();

        // 2. Persist the invoice stub inside a clean, short database transaction boundaries
        Invoice invoice = this.getOrCreatePendingInvoice(tenantId, reference, amountKobo, plan, period);

        try {
            log.info("Executing external transaction initialization with Paystack for ref: {}", reference);

            // 3. Perform network I/O outside of any active database lock contexts
            PaystackResponse.Data initData = paystackClient.initializeTransaction(
                    billingEmail, invoice.getAmountKobo(), reference, transactionCallbackUrl);

            // 4. Return integration routes out to the client
            return Map.of(
                    "authorization_url", initData.getAuthorizationUrl(),
                    "reference", reference
            );

        } catch (Exception ex) {
            log.error("Network initialization rejected by Paystack for reference: {}. Reason: {}", reference, ex.getMessage());
            // Log failure to the DB record using an isolated propagation route so it updates regardless
            this.markInvoiceInitializationFailed(reference, ex.getMessage());
            throw new IllegalStateException("Failed to negotiate transaction routing with payment infrastructure. Please try again.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Invoice getOrCreatePendingInvoice(UUID tenantId, String reference, long amountKobo, SubscriptionPlan plan, PaymentPeriod period) {
        // If an invoice with this precise plan configuration exists as PENDING, reuse it.
        return invoiceRepository.findByPaystackReference(reference)
                .filter(existing -> existing.getStatus() == PaymentStatus.PENDING)
                .orElseGet(() -> {
                    Tenant tenant = tenantRepository.findById(tenantId)
                            .orElseThrow(() -> new IllegalArgumentException("Tenant context validation failed"));

                    log.info("Registering PENDING ledger stub for tracking ref: {}", reference);
                    return invoiceRepository.save(Invoice.builder()
                            .tenant(tenant)
                            .paystackReference(reference)
                            .amountKobo(amountKobo)
                            .status(PaymentStatus.PENDING)
                            .plan(plan)
                            .period(period)
                            .build());
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInvoiceInitializationFailed(String reference, String reason) {
        invoiceRepository.findByPaystackReference(reference).ifPresent(invoice -> {
            invoice.setFailureReason("Initialization failure: " + reason);
            invoiceRepository.save(invoice);
        });
    }
}