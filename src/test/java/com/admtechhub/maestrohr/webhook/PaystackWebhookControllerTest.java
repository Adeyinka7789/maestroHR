package com.admtechhub.maestrohr.webhook;

import com.admtechhub.maestrohr.payment.Invoice;
import com.admtechhub.maestrohr.payment.InvoiceRepository;
import com.admtechhub.maestrohr.payment.PaymentStatus;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.admtechhub.maestrohr.subscription.TenantSubscription;
import com.admtechhub.maestrohr.subscription.TenantSubscriptionRepository;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B webhook tests. Exercise the controller method directly (rather than through
 * MockMvc) so the whole flow runs inside the test's transaction — that way the PENDING
 * invoice the test inserts is visible to {@code handleChargeSuccess}, and {@code @Transactional}
 * rolls everything back afterwards. The HMAC signature is computed with the same secret
 * the bean is configured with via {@link TestPropertySource}.
 *
 * <p>Fixtures are tagged {@code TEST-PHASE-B …} for identifiability if a row ever survives.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "paystack.webhook.verify-signature=true",
        "paystack.secret-key=sk_test_phaseb_secret"
})
class PaystackWebhookControllerTest {

    private static final String SECRET = "sk_test_phaseb_secret";

    @Autowired private PaystackWebhookController controller;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Autowired private EntityManager entityManager;

    // ── helpers ───────────────────────────────────────────────────────────────
    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private String chargeSuccessPayload(String reference, long amountKobo) {
        return "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference
                + "\",\"amount\":" + amountKobo
                + ",\"customer\":{\"customer_code\":\"CUS_phaseb\"}}}";
    }

    private Tenant persistTenant() {
        Tenant tenant = Tenant.builder()
                .companyName("TEST-PHASE-B Webhook Tenant")
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .paymentPeriod(PaymentPeriod.MONTHLY)
                .subscriptionExpiresAt(OffsetDateTime.now().minusDays(1)) // expired trial
                .build();
        return tenantRepository.saveAndFlush(tenant);
    }

    private Invoice persistPendingInvoice(Tenant tenant, String reference, long amountKobo) {
        return invoiceRepository.saveAndFlush(Invoice.builder()
                .tenant(tenant)
                .paystackReference(reference)
                .amountKobo(amountKobo)
                .status(PaymentStatus.PENDING)
                .plan(SubscriptionPlan.PROFESSIONAL)
                .period(PaymentPeriod.MONTHLY)
                .build());
    }

    // ── valid signature + new reference → invoice SUCCESS, subscription ACTIVE, 200 ──
    @Test
    void validSignature_newReference_marksInvoiceSuccessAndActivatesSubscription() throws Exception {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        String reference = "TEST-PHASE-B-NEW-001";
        persistPendingInvoice(tenant, reference, 7_500_000L);

        String payload = chargeSuccessPayload(reference, 7_500_000L);
        ResponseEntity<String> response = controller.handlePaystackWebhook(payload, sign(payload));

        assertEquals(200, response.getStatusCode().value(), "accepted webhook returns 200");

        bindTenant(tenant.getId());

        Invoice updated = invoiceRepository.findByPaystackReference(reference).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, updated.getStatus(), "invoice flipped to SUCCESS");
        assertTrue(updated.getPaidAt() != null, "paidAt set");

        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenant.getId()).orElseThrow();
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus(), "subscription activated");
        assertEquals(SubscriptionPlan.PROFESSIONAL, sub.getPlan(), "plan from invoice applied");
    }

    // ── valid signature + duplicate delivery → idempotent (no second mutation), 200 ──
    @Test
    void validSignature_duplicateDelivery_isIdempotent() throws Exception {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        String reference = "TEST-PHASE-B-DUP-001";
        persistPendingInvoice(tenant, reference, 7_500_000L);

        String payload = chargeSuccessPayload(reference, 7_500_000L);
        String signature = sign(payload);

        ResponseEntity<String> first = controller.handlePaystackWebhook(payload, signature);
        assertEquals(200, first.getStatusCode().value());

        bindTenant(tenant.getId());
        OffsetDateTime paidAtAfterFirst =
                invoiceRepository.findByPaystackReference(reference).orElseThrow().getPaidAt();

        // Replay the exact same webhook.
        ResponseEntity<String> second = controller.handlePaystackWebhook(payload, signature);
        assertEquals(200, second.getStatusCode().value(), "duplicate still returns 200");

        bindTenant(tenant.getId());
        Invoice updated = invoiceRepository.findByPaystackReference(reference).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, updated.getStatus());
        assertEquals(paidAtAfterFirst, updated.getPaidAt(),
                "paidAt unchanged on replay — second delivery was a no-op");
    }

    // ── invalid signature → 401, nothing written ────────────────────────────────
    @Test
    void invalidSignature_returns401_andWritesNothing() throws Exception {
        Tenant tenant = persistTenant();
        bindTenant(tenant.getId());
        String reference = "TEST-PHASE-B-BADSIG-001";
        persistPendingInvoice(tenant, reference, 7_500_000L);

        String payload = chargeSuccessPayload(reference, 7_500_000L);
        ResponseEntity<String> response =
                controller.handlePaystackWebhook(payload, "deadbeef-not-a-valid-signature");

        assertEquals(401, response.getStatusCode().value(), "forged signature rejected");

        bindTenant(tenant.getId());
        Invoice untouched = invoiceRepository.findByPaystackReference(reference).orElseThrow();
        assertEquals(PaymentStatus.PENDING, untouched.getStatus(), "invoice left untouched");
        Optional<TenantSubscription> sub = tenantSubscriptionRepository.findByTenantId(tenant.getId());
        assertTrue(sub.isEmpty(), "no subscription created on rejected webhook");
    }

    // ── missing signature → 401 ─────────────────────────────────────────────────
    @Test
    void missingSignature_returns401() {
        String payload = chargeSuccessPayload("TEST-PHASE-B-NOSIG-001", 7_500_000L);
        ResponseEntity<String> response = controller.handlePaystackWebhook(payload, null);
        assertEquals(401, response.getStatusCode().value());
    }
}
