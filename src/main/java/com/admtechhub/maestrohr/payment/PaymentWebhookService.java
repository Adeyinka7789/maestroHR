package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.payment.dto.PaystackWebhookPayload;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.admtechhub.maestrohr.tenant.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    private final TenantRepository tenantRepository;
    private final SubscriptionService subscriptionService;
    private final PricingService pricingService;
    private final InvoiceRepository invoiceRepository;
    private final EntityManager entityManager;

    /**
     * Handle subscription creation event
     */
    @Transactional
    public void handleSubscriptionCreate(PaystackWebhookPayload payload) {
        log.info("Processing subscription.create event");

        var data = payload.getData();
        String subscriptionCode = data.getSubscriptionCode();
        String customerCode = data.getCustomer().getCustomerCode();

        // Find tenant by customer code
        Optional<Tenant> tenantOpt = tenantRepository.findByPaystackCustomerCode(customerCode);

        if (tenantOpt.isEmpty()) {
            log.warn("No tenant found for customer code: {}", customerCode);
            return;
        }

        Tenant tenant = tenantOpt.get();

        // Determine plan from amount
        SubscriptionPlan plan = determinePlanFromAmount(data.getAmount());
        PaymentPeriod period = determinePeriodFromInterval(data.getPlan().getInterval());

        tenant.setPaystackSubscriptionCode(subscriptionCode);
        tenant.setSubscriptionPlan(plan);
        tenant.setPaymentPeriod(period);
        tenant.setAutoRenew(true);

        // Set expiry based on period
        OffsetDateTime now = OffsetDateTime.now();
        tenant.setSubscriptionExpiresAt(now.plusMonths(period.getMonths()));
        tenant.setActive(true);

        tenantRepository.save(tenant);
        subscriptionService.syncSubscriptionState(tenant.getId(), SubscriptionStatus.ACTIVE);

        log.info("Subscription created for tenant {}: Plan={}, Period={}, Expires={}",
                tenant.getId(), plan, period, tenant.getSubscriptionExpiresAt());
    }

    /**
     * Handle charge success event (payment received).
     *
     * <p>The tenant is resolved from the transaction reference — the {@link Invoice}
     * created at initialize-time owns that reference. This is more reliable than the
     * Paystack customer code for the one-off checkout flow (a customer code may not yet
     * be stored). The operation is idempotent on the reference: Paystack can deliver the
     * same webhook more than once, and a charge.success for an already-SUCCESS invoice is
     * a no-op.
     */
    @Transactional
    public void handleChargeSuccess(PaystackWebhookPayload payload) {
        log.info("Processing charge.success event");

        var data = payload.getData();
        String reference = data.getReference();
        if (reference == null || reference.isBlank()) {
            log.warn("charge.success without a reference; ignoring");
            return;
        }

        // Resolve the owning tenant WITHOUT a tenant session (webhooks have none); this
        // native lookup bypasses @SQLRestriction. Then bind the session so the scoped
        // repositories below resolve to the correct tenant.
        Optional<UUID> tenantIdOpt = invoiceRepository.findTenantIdByPaystackReference(reference);
        if (tenantIdOpt.isEmpty()) {
            log.warn("charge.success for unknown reference {} — no matching invoice", reference);
            return;
        }
        UUID tenantId = tenantIdOpt.get();
        bindTenantSession(tenantId);

        Invoice invoice = invoiceRepository.findByPaystackReference(reference).orElse(null);
        if (invoice == null) {
            log.warn("charge.success: invoice not visible for reference {} after binding tenant {}",
                    reference, tenantId);
            return;
        }

        // Idempotency: already processed → do nothing, return 200.
        if (invoice.getStatus() == PaymentStatus.SUCCESS) {
            log.info("charge.success for {} already processed; ignoring (idempotent)", reference);
            return;
        }

        // Mark the invoice paid.
        invoice.setStatus(PaymentStatus.SUCCESS);
        invoice.setPaidAt(OffsetDateTime.now());
        invoiceRepository.save(invoice);

        // Activate / extend the tenant's subscription for the purchased plan + period.
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        tenant.setSubscriptionPlan(invoice.getPlan());
        tenant.setPaymentPeriod(invoice.getPeriod());

        int monthsToAdd = invoice.getPeriod() != null ? invoice.getPeriod().getMonths() : 1;
        OffsetDateTime newExpiry = OffsetDateTime.now().plusMonths(monthsToAdd);
        tenant.setSubscriptionExpiresAt(newExpiry);
        tenant.setActive(true);
        tenantRepository.save(tenant);

        subscriptionService.syncSubscriptionState(tenantId, SubscriptionStatus.ACTIVE);

        log.info("charge.success processed for tenant {}: plan={}, period={}, expiry={}, reference={}",
                tenantId, invoice.getPlan(), invoice.getPeriod(), newExpiry, reference);
    }

    /**
     * Handle subscription disable event (cancellation or payment failure)
     */
    @Transactional
    public void handleSubscriptionDisable(PaystackWebhookPayload payload) {
        log.info("Processing subscription.disable event");

        var data = payload.getData();
        String subscriptionCode = data.getSubscriptionCode();

        Optional<Tenant> tenantOpt = tenantRepository.findByPaystackSubscriptionCode(subscriptionCode);

        if (tenantOpt.isEmpty()) {
            log.warn("No tenant found for subscription code: {}", subscriptionCode);
            return;
        }

        Tenant tenant = tenantOpt.get();

        // Downgrade to FREE_TRIAL but mark as inactive
        tenant.setSubscriptionPlan(SubscriptionPlan.FREE_TRIAL);
        tenant.setActive(false);
        tenant.setAutoRenew(false);

        tenantRepository.save(tenant);
        subscriptionService.syncSubscriptionState(tenant.getId(), SubscriptionStatus.CANCELLED);

        log.info("Subscription disabled for tenant {}", tenant.getId());
    }

    /**
     * Handle invoice creation
     */
    @Transactional
    public void handleInvoiceCreate(PaystackWebhookPayload payload) {
        log.info("Processing invoice.create event");
        // Log for record, no action needed
        var data = payload.getData();
        log.debug("Invoice created: reference={}, amount={}",
                data.getReference(), data.getAmount());
    }

    /**
     * Handle invoice payment failed
     */
    @Transactional
    public void handleInvoicePaymentFailed(PaystackWebhookPayload payload) {
        log.warn("Processing invoice.payment_failed event");

        var data = payload.getData();
        String customerCode = data.getCustomer().getCustomerCode();

        Optional<Tenant> tenantOpt = tenantRepository.findByPaystackCustomerCode(customerCode);

        if (tenantOpt.isPresent()) {
            Tenant tenant = tenantOpt.get();
            log.warn("Payment failed for tenant {}. Subscription may be disabled soon.",
                    tenant.getId());

            subscriptionService.syncSubscriptionState(tenant.getId(), SubscriptionStatus.PAST_DUE);

            // Send notification to tenant (implement later)
        }
    }

    /**
     * Determine SubscriptionPlan from amount in kobo by reverse-looking-up the price in
     * pricing_config (the single source of truth for prices), falling back to BASIC.
     */
    private SubscriptionPlan determinePlanFromAmount(Long amountKobo) {
        return pricingService.findPlanNameByPrice(amountKobo)
                .map(SubscriptionPlan::valueOf)
                .orElse(SubscriptionPlan.BASIC);
    }

    /**
     * Bind {@code app.current_tenant} for the rest of this transaction so the
     * {@code @SQLRestriction} on billing entities resolves to the target tenant, even
     * though the webhook request itself carries no tenant context. Transaction-local,
     * so it reverts on commit/rollback. Mirrors {@code SubscriptionService}.
     */
    private void bindTenantSession(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    /**
     * Determine PaymentPeriod from interval string
     */
    private PaymentPeriod determinePeriodFromInterval(String interval) {
        if (interval == null) return PaymentPeriod.MONTHLY;

        return switch (interval.toLowerCase()) {
            case "monthly" -> PaymentPeriod.MONTHLY;
            case "quarterly" -> PaymentPeriod.QUARTERLY;
            case "annually" -> PaymentPeriod.ANNUALLY;
            default -> PaymentPeriod.MONTHLY;
        };
    }
}