package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.payment.dto.PaystackWebhookPayload;
import com.admtechhub.maestrohr.platform.WebhookTenantResolver;
import com.admtechhub.maestrohr.payroll.*;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.admtechhub.maestrohr.tenant.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
    private final WebhookTenantResolver webhookTenantResolver;
    private final EntityManager entityManager;
    private final PayrollEntryRepository payrollEntryRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final NotificationService notificationService;

    /**
     * Handle subscription creation event
     */
    @Transactional
    public void handleSubscriptionCreate(PaystackWebhookPayload payload) {
        log.info("Processing subscription.create event");

        var data = payload.getData();
        String subscriptionCode = data.getSubscriptionCode();
        String customerCode = data.getCustomer().getCustomerCode();

        // Resolve the owning tenant WITHOUT a tenant session (webhooks have none) through the
        // privileged datasource, then bind that tenant's session so the scoped JPA mutation
        // below resolves under the RLS-enforced primary role.
        Optional<UUID> tenantIdOpt = webhookTenantResolver.findTenantIdByCustomerCode(customerCode);
        if (tenantIdOpt.isEmpty()) {
            log.warn("No tenant found for customer code: {}", customerCode);
            return;
        }
        UUID tenantId = tenantIdOpt.get();
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

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

        // Resolve the owning tenant WITHOUT a tenant session (webhooks have none) through the
        // privileged datasource — under the RLS-enforced primary role the scoped lookup would
        // see nothing. Then bind the session so the scoped repositories below resolve to the
        // correct tenant.
        Optional<UUID> tenantIdOpt = webhookTenantResolver.findTenantIdByInvoiceReference(reference);
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

        // Privileged resolve (no tenant session) → bind → scoped JPA mutation.
        Optional<UUID> tenantIdOpt =
                webhookTenantResolver.findTenantIdBySubscriptionCode(subscriptionCode);
        if (tenantIdOpt.isEmpty()) {
            log.warn("No tenant found for subscription code: {}", subscriptionCode);
            return;
        }
        UUID tenantId = tenantIdOpt.get();
        bindTenantSession(tenantId);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

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

        // Privileged resolve (no tenant session); syncSubscriptionState binds the tenant
        // session itself before its scoped upsert.
        Optional<UUID> tenantIdOpt = webhookTenantResolver.findTenantIdByCustomerCode(customerCode);

        if (tenantIdOpt.isPresent()) {
            UUID tenantId = tenantIdOpt.get();
            log.warn("Payment failed for tenant {}. Subscription may be disabled soon.", tenantId);

            subscriptionService.syncSubscriptionState(tenantId, SubscriptionStatus.PAST_DUE);

            // Send notification to tenant (implement later)
        }
    }

    /**
     * Handle transfer.success: marks the matching PayrollEntry PAID, auto-completes the run
     * when all entries are paid, and notifies the employee via SMS + email.
     */
    @Transactional
    public void handleTransferSuccess(PaystackWebhookPayload payload) {
        String reference = payload.getData().getReference();
        if (reference == null || reference.isBlank()) {
            log.warn("transfer.success without a reference; ignoring");
            return;
        }

        Optional<UUID> tenantIdOpt = webhookTenantResolver.findTenantIdByTransferReference(reference);
        if (tenantIdOpt.isEmpty()) {
            log.debug("transfer.success for reference {} — no matching payroll entry, skipping", reference);
            return;
        }
        UUID tenantId = tenantIdOpt.get();
        bindTenantSession(tenantId);

        PayrollEntry entry = payrollEntryRepository.findByTransferReference(reference).orElse(null);
        if (entry == null) {
            log.warn("transfer.success: entry not visible for reference {} after binding tenant {}", reference, tenantId);
            return;
        }
        if (entry.getTransferStatus() == TransferStatus.PAID) {
            log.info("transfer.success for {} already processed; ignoring (idempotent)", reference);
            return;
        }

        entry.setTransferStatus(TransferStatus.PAID);
        payrollEntryRepository.save(entry);

        PayrollRun payrollRun = entry.getPayrollRun();
        long pendingCount = payrollEntryRepository.countByTransferStatus(payrollRun.getId(), TransferStatus.PENDING);
        if (pendingCount == 0 && payrollRun.canComplete()) {
            payrollRun.setStatus(PayrollStatus.COMPLETED);
            payrollRunRepository.save(payrollRun);
            log.info("All transfers completed for payroll run {}; status → COMPLETED", payrollRun.getId());
        }

        String companyName = payrollRun.getTenant().getCompanyName();
        notificationService.sendSalaryCreditNotification(entry, entry.getEmployee(), payrollRun.getPeriod(), companyName);
        log.info("transfer.success processed: entry {} → PAID, employee {} notified", entry.getId(), entry.getEmployee().getEmployeeNumber());
    }

    /**
     * Handle transfer.failed: marks the PayrollEntry FAILED and notifies HR_ADMINs via
     * in-app notification so they can investigate and retry.
     */
    @Transactional
    public void handleTransferFailed(PaystackWebhookPayload payload) {
        String reference = payload.getData().getReference();
        if (reference == null || reference.isBlank()) {
            log.warn("transfer.failed without a reference; ignoring");
            return;
        }

        Optional<UUID> tenantIdOpt = webhookTenantResolver.findTenantIdByTransferReference(reference);
        if (tenantIdOpt.isEmpty()) {
            log.debug("transfer.failed for reference {} — no matching payroll entry, skipping", reference);
            return;
        }
        UUID tenantId = tenantIdOpt.get();
        bindTenantSession(tenantId);

        PayrollEntry entry = payrollEntryRepository.findByTransferReference(reference).orElse(null);
        if (entry == null) {
            log.warn("transfer.failed: entry not visible for reference {} after binding tenant {}", reference, tenantId);
            return;
        }

        entry.setTransferStatus(TransferStatus.FAILED);
        payrollEntryRepository.save(entry);

        String employeeName = entry.getEmployee().getFullName();
        String period = entry.getPayrollRun().getPeriod();
        String alertMessage = String.format("Salary transfer failed for %s (%s). Reference: %s",
                employeeName, period, reference);

        List<String> hrEmails = webhookTenantResolver.findHrAdminEmails(tenantId);
        for (String hrEmail : hrEmails) {
            notificationService.createInAppNotification(hrEmail,
                    "TRANSFER_FAILED", "Salary transfer failed", alertMessage,
                    "/payroll/" + entry.getPayrollRun().getId());
        }

        log.info("transfer.failed processed: entry {} → FAILED, {} HR admin(s) notified", entry.getId(), hrEmails.size());
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