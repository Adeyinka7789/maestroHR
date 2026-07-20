package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.tenant.AppliedDiscount;
import com.admtechhub.maestrohr.tenant.DiscountService;
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

import java.time.OffsetDateTime;
import java.util.List;
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
    private final PaymentWebhookService paymentWebhookService;
    private final DiscountService discountService;
    private final SubscriptionService subscriptionService;

    @Value("${paystack.transaction-callback-url:${app.url}/api/payment/callback}")
    private String transactionCallbackUrl;

    /**
     * Entry method: Intentionally NOT annotated with global @Transactional.
     * This isolates database transactions from the Paystack API network call.
     */
    public Map<String, String> prepareAndInitializePayment(UUID tenantId, String billingEmail, String rawPlan, String rawPeriod) {
        SubscriptionPlan plan = SubscriptionPlan.valueOf(rawPlan.toUpperCase());
        PaymentPeriod period = PaymentPeriod.valueOf(rawPeriod.toUpperCase());

        long baseKobo = pricingService.getPrice(plan.name(), period.name());
        if (baseKobo <= 0) {
            throw new IllegalArgumentException("Plan " + plan + "/" + period + " is not payable");
        }

        // Resolve the best admin-configured discount SERVER-SIDE (never trust the client). The
        // net amount is what we actually charge and store on the invoice; the discount is recorded
        // for the receipt.
        AppliedDiscount applied = discountService.resolveBestDiscount(tenantId, plan.name(), period.name(), baseKobo);

        // 1. Generate an un-hashed reference based on target details to allow safe idempotency retries.
        // If a user clicks 'Pay' twice, it will reuse the exact pending invoice record instead of creating duplicates.
        String reference = "SUB_" + tenantId.toString().substring(0, 8) + "_" + plan.name() + "_" + period.name();

        // A discount that zeroes the price cannot go through Paystack (it rejects a ₦0 charge).
        // Activate the subscription immediately and record a paid ₦0 invoice for the receipt.
        if (applied.netKobo() <= 0) {
            this.activateFreeSubscription(tenantId, reference, plan, period, applied);
            return Map.of(
                    "redirect", "/htmx/settings?payment=success",
                    "reference", reference
            );
        }

        // 2. Persist the invoice stub inside a clean, short database transaction boundaries
        Invoice invoice = this.getOrCreatePendingInvoice(tenantId, reference, plan, period, applied);

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
    public Invoice getOrCreatePendingInvoice(UUID tenantId, String reference,
                                             SubscriptionPlan plan, PaymentPeriod period, AppliedDiscount applied) {
        // If an invoice with this precise plan configuration exists as PENDING, reuse it — but
        // refresh its amount/discount so a since-changed price or discount is reflected in the charge.
        return invoiceRepository.findByPaystackReference(reference)
                .filter(existing -> existing.getStatus() == PaymentStatus.PENDING)
                .map(existing -> {
                    existing.setAmountKobo(applied.netKobo());
                    existing.setDiscountKobo(applied.discountKobo());
                    existing.setDiscountLabel(applied.label());
                    return invoiceRepository.save(existing);
                })
                .orElseGet(() -> {
                    Tenant tenant = tenantRepository.findById(tenantId)
                            .orElseThrow(() -> new IllegalArgumentException("Tenant context validation failed"));

                    log.info("Registering PENDING ledger stub for tracking ref: {}", reference);
                    return invoiceRepository.save(Invoice.builder()
                            .tenant(tenant)
                            .paystackReference(reference)
                            .amountKobo(applied.netKobo())
                            .discountKobo(applied.discountKobo())
                            .discountLabel(applied.label())
                            .status(PaymentStatus.PENDING)
                            .plan(plan)
                            .period(period)
                            .build());
                });
    }

    /**
     * Fully-discounted (net ₦0) checkout: there is nothing to charge, so activate the plan
     * directly via {@link SubscriptionService#upgradePlan} and record a SUCCESS ₦0 invoice so the
     * receipt still appears. REQUIRES_NEW to mirror {@link #getOrCreatePendingInvoice}'s isolated
     * write boundary; the tenant session is bound by DataSourceConfig from the caller's context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activateFreeSubscription(UUID tenantId, String reference,
                                         SubscriptionPlan plan, PaymentPeriod period, AppliedDiscount applied) {
        Invoice invoice = invoiceRepository.findByPaystackReference(reference)
                .orElseGet(() -> Invoice.builder()
                        .tenant(tenantRepository.findById(tenantId)
                                .orElseThrow(() -> new IllegalArgumentException("Tenant context validation failed")))
                        .paystackReference(reference)
                        .plan(plan)
                        .period(period)
                        .build());
        invoice.setAmountKobo(0L);
        invoice.setDiscountKobo(applied.discountKobo());
        invoice.setDiscountLabel(applied.label());
        invoice.setStatus(PaymentStatus.SUCCESS);
        invoice.setPaidAt(OffsetDateTime.now());
        invoiceRepository.save(invoice);

        subscriptionService.upgradePlan(tenantId, plan, period);
        log.info("Free activation (discount '{}') for tenant {}: {}/{}", applied.label(), tenantId, plan, period);
    }

    /**
     * Backs the browser return-from-Paystack callback ({@code GET /api/payment/callback}).
     * That callback is a public, unauthenticated redirect target, so it must NOT trust the
     * redirect itself — it confirms the payment directly with Paystack ({@code verifyTransaction})
     * before activating anything. On a verified {@code "success"} it delegates to the shared,
     * idempotent {@link PaymentWebhookService#activateSubscriptionForReference} (a cross-bean
     * call, so its {@code @Transactional} boundary applies). Deliberately NOT {@code @Transactional}:
     * the Paystack network call must stay outside any DB transaction, exactly like
     * {@link #prepareAndInitializePayment}.
     *
     * @return a short status token used to build the redirect query param
     *         ({@code success} | {@code pending} | {@code failed} | {@code verify_error}).
     */
    public String verifyAndActivate(String reference) {
        PaystackResponse.Data data;
        try {
            data = paystackClient.verifyTransaction(reference);
        } catch (Exception ex) {
            log.error("Callback verification failed for reference {}: {}", reference, ex.getMessage(), ex);
            return "verify_error";
        }

        if (data == null || !"success".equalsIgnoreCase(data.getStatus())) {
            log.warn("Callback: reference {} did not verify as successful (status={})",
                    reference, data != null ? data.getStatus() : "null");
            return "failed";
        }

        boolean activated = paymentWebhookService.activateSubscriptionForReference(reference);
        return activated ? "success" : "pending";
    }

    /**
     * On-demand reconciliation of the current tenant's {@code PENDING} invoices.
     *
     * <p>Every "Pay" click writes a {@code PENDING} invoice stub <em>before</em> the Paystack call
     * ({@link #getOrCreatePendingInvoice}), and there is no background job that later reconciles it.
     * A stub therefore lingers as {@code PENDING} whenever the browser never returns to the
     * {@code /api/payment/callback} redirect — e.g. the checkout tab was closed, or (on localhost)
     * the inbound {@code charge.success} webhook can never reach the app. This action closes that
     * gap: for each still-{@code PENDING} invoice it re-confirms the charge directly with Paystack
     * via {@link #verifyAndActivate} and activates the subscription when Paystack reports success.
     *
     * <p>Safe and idempotent: verification is read-only, activation no-ops on an already-{@code SUCCESS}
     * invoice, and an invoice Paystack does not report as paid is simply left {@code PENDING}.
     *
     * <p>Deliberately NOT {@code @Transactional} — it performs Paystack network I/O per invoice, and
     * each {@link #verifyAndActivate} activation opens its own transaction through the Spring proxy.
     * The pending list is read up front (under the caller's tenant session) so the per-invoice tenant
     * re-binding inside activation cannot affect the iteration.
     *
     * @return a summary of how many invoices were checked, activated, still pending, or errored.
     */
    public ReconcileResult reconcilePendingInvoices() {
        List<Invoice> pending = invoiceRepository.findByStatusOrderByCreatedAtDesc(PaymentStatus.PENDING);
        int activated = 0;
        int stillPending = 0;
        int errored = 0;
        for (Invoice invoice : pending) {
            String status = verifyAndActivate(invoice.getPaystackReference());
            switch (status) {
                case "success" -> activated++;
                case "verify_error" -> errored++;
                default -> stillPending++; // "pending" | "failed"
            }
        }
        log.info("Reconcile: {} pending invoice(s) checked — {} activated, {} still pending, {} errored",
                pending.size(), activated, stillPending, errored);
        return new ReconcileResult(pending.size(), activated, stillPending, errored);
    }

    /** Outcome of a {@link #reconcilePendingInvoices()} sweep. */
    public record ReconcileResult(int checked, int activated, int stillPending, int errored) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInvoiceInitializationFailed(String reference, String reason) {
        invoiceRepository.findByPaystackReference(reference).ifPresent(invoice -> {
            invoice.setFailureReason("Initialization failure: " + reason);
            invoiceRepository.save(invoice);
        });
    }
}