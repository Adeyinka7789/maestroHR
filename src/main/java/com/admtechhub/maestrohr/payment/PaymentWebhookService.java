package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.payment.dto.PaystackWebhookPayload;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.tenant.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    private final TenantRepository tenantRepository;
    private final SubscriptionService subscriptionService;

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

        log.info("Subscription created for tenant {}: Plan={}, Period={}, Expires={}",
                tenant.getId(), plan, period, tenant.getSubscriptionExpiresAt());
    }

    /**
     * Handle charge success event (payment received)
     */
    @Transactional
    public void handleChargeSuccess(PaystackWebhookPayload payload) {
        log.info("Processing charge.success event");

        var data = payload.getData();
        String customerCode = data.getCustomer().getCustomerCode();

        Optional<Tenant> tenantOpt = tenantRepository.findByPaystackCustomerCode(customerCode);

        if (tenantOpt.isEmpty()) {
            log.warn("No tenant found for customer code: {}", customerCode);
            return;
        }

        Tenant tenant = tenantOpt.get();

        // Extend subscription by the period
        int monthsToAdd = tenant.getPaymentPeriod() != null ?
                tenant.getPaymentPeriod().getMonths() : 1;

        OffsetDateTime newExpiry = OffsetDateTime.now().plusMonths(monthsToAdd);
        tenant.setSubscriptionExpiresAt(newExpiry);
        tenant.setActive(true);

        tenantRepository.save(tenant);

        log.info("Payment received for tenant {}. New expiry: {}",
                tenant.getId(), newExpiry);
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

            // Send notification to tenant (implement later)
        }
    }

    /**
     * Determine SubscriptionPlan from amount in kobo
     */
    private SubscriptionPlan determinePlanFromAmount(Long amountKobo) {
        if (amountKobo == null) return SubscriptionPlan.BASIC;

        return switch (amountKobo.intValue()) {
            case 25000 -> SubscriptionPlan.BASIC;
            case 75000 -> SubscriptionPlan.PROFESSIONAL;
            case 200000 -> SubscriptionPlan.ENTERPRISE;
            default -> SubscriptionPlan.BASIC;
        };
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