package com.admtechhub.maestrohr.webhook;

import com.admtechhub.maestrohr.payment.Invoice;
import com.admtechhub.maestrohr.payment.InvoiceRepository;
import com.admtechhub.maestrohr.payment.PaymentStatus;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B webhook tests, updated for E4c-i.
 *
 * <p><b>Why no longer {@code @Transactional}.</b> {@code handleChargeSuccess} now resolves the
 * owning tenant through the <em>privileged</em> datasource (a separate connection pool), which
 * cannot see rows from an uncommitted test transaction. Fixtures are therefore committed here
 * (the repository {@code saveAndFlush} calls run in their own transactions with no ambient test
 * transaction) and removed in {@link #cleanup()} via the privileged template, which bypasses
 * {@code @SQLRestriction} and RLS. Post-webhook state is asserted with the same privileged
 * template, so no tenant session needs binding to read it back. Mirrors {@code PrivilegedQueriesTest}.
 *
 * <p>The HMAC signature is computed with the same secret the bean is configured with via
 * {@link TestPropertySource}. Fixtures are tagged {@code TEST-PHASE-B …} for identifiability if
 * a row ever survives a failed cleanup.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "paystack.webhook.verify-signature=true",
        "paystack.secret-key=sk_test_phaseb_secret"
})
class PaystackWebhookControllerTest {

    private static final String SECRET = "sk_test_phaseb_secret";

    @Autowired private PaystackWebhookController controller;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired @Qualifier("privilegedJdbcTemplate") private JdbcTemplate privilegedJdbc;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    @BeforeEach
    void cleanupBefore() {
        // Delete any rows left by a previous crashed run (FK order: children first).
        privilegedJdbc.update("DELETE FROM invoices WHERE paystack_reference LIKE 'TEST-PHASE-B-%'");
        privilegedJdbc.update(
                "DELETE FROM tenant_subscriptions WHERE tenant_id IN "
                + "(SELECT id FROM tenants WHERE company_name = 'TEST-PHASE-B Webhook Tenant')");
        privilegedJdbc.update("DELETE FROM tenants WHERE company_name = 'TEST-PHASE-B Webhook Tenant'");
    }

    @AfterEach
    void cleanup() {
        // Privileged template bypasses @SQLRestriction + RLS, so DELETEs hit every row.
        for (UUID tenantId : createdTenantIds) {
            privilegedJdbc.update("DELETE FROM invoices WHERE tenant_id = ?", tenantId);
            privilegedJdbc.update("DELETE FROM tenant_subscriptions WHERE tenant_id = ?", tenantId);
            privilegedJdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
        createdTenantIds.clear();
    }

    // ── helpers ───────────────────────────────────────────────────────────────
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

    /** Commits a tenant fixture (no ambient test transaction) and tracks it for cleanup. */
    private Tenant persistTenant() {
        Tenant tenant = tenantRepository.saveAndFlush(Tenant.builder()
                .companyName("TEST-PHASE-B Webhook Tenant")
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .paymentPeriod(PaymentPeriod.MONTHLY)
                .subscriptionExpiresAt(OffsetDateTime.now().minusDays(1)) // expired trial
                .build());
        createdTenantIds.add(tenant.getId());
        return tenant;
    }

    private void persistPendingInvoice(Tenant tenant, String reference, long amountKobo) {
        invoiceRepository.saveAndFlush(Invoice.builder()
                .tenant(tenant)
                .paystackReference(reference)
                .amountKobo(amountKobo)
                .status(PaymentStatus.PENDING)
                .plan(SubscriptionPlan.PROFESSIONAL)
                .period(PaymentPeriod.MONTHLY)
                .build());
    }

    private String invoiceStatus(String reference) {
        return privilegedJdbc.queryForObject(
                "SELECT status FROM invoices WHERE paystack_reference = ?", String.class, reference);
    }

    private Timestamp invoicePaidAt(String reference) {
        return privilegedJdbc.queryForObject(
                "SELECT paid_at FROM invoices WHERE paystack_reference = ?", Timestamp.class, reference);
    }

    private List<Map<String, Object>> subscriptionsFor(UUID tenantId) {
        return privilegedJdbc.queryForList(
                "SELECT status, plan FROM tenant_subscriptions WHERE tenant_id = ?", tenantId);
    }

    // ── valid signature + new reference → invoice SUCCESS, subscription ACTIVE, 200 ──
    @Test
    void validSignature_newReference_marksInvoiceSuccessAndActivatesSubscription() throws Exception {
        Tenant tenant = persistTenant();
        String reference = "TEST-PHASE-B-NEW-001";
        persistPendingInvoice(tenant, reference, 7_500_000L);

        String payload = chargeSuccessPayload(reference, 7_500_000L);
        ResponseEntity<String> response = controller.handlePaystackWebhook(payload, sign(payload));

        assertEquals(200, response.getStatusCode().value(), "accepted webhook returns 200");

        assertEquals("SUCCESS", invoiceStatus(reference), "invoice flipped to SUCCESS");
        assertNotNull(invoicePaidAt(reference), "paidAt set");

        List<Map<String, Object>> subs = subscriptionsFor(tenant.getId());
        assertEquals(1, subs.size(), "one subscription created");
        assertEquals("ACTIVE", subs.get(0).get("status"), "subscription activated");
        assertEquals("PROFESSIONAL", subs.get(0).get("plan"), "plan from invoice applied");
    }

    // ── valid signature + duplicate delivery → idempotent (no second mutation), 200 ──
    @Test
    void validSignature_duplicateDelivery_isIdempotent() throws Exception {
        Tenant tenant = persistTenant();
        String reference = "TEST-PHASE-B-DUP-001";
        persistPendingInvoice(tenant, reference, 7_500_000L);

        String payload = chargeSuccessPayload(reference, 7_500_000L);
        String signature = sign(payload);

        ResponseEntity<String> first = controller.handlePaystackWebhook(payload, signature);
        assertEquals(200, first.getStatusCode().value());
        Timestamp paidAtAfterFirst = invoicePaidAt(reference);

        // Replay the exact same webhook.
        ResponseEntity<String> second = controller.handlePaystackWebhook(payload, signature);
        assertEquals(200, second.getStatusCode().value(), "duplicate still returns 200");

        assertEquals("SUCCESS", invoiceStatus(reference));
        assertEquals(paidAtAfterFirst, invoicePaidAt(reference),
                "paidAt unchanged on replay — second delivery was a no-op");
    }

    // ── invalid signature → 401, nothing written ────────────────────────────────
    @Test
    void invalidSignature_returns401_andWritesNothing() throws Exception {
        Tenant tenant = persistTenant();
        String reference = "TEST-PHASE-B-BADSIG-001";
        persistPendingInvoice(tenant, reference, 7_500_000L);

        String payload = chargeSuccessPayload(reference, 7_500_000L);
        ResponseEntity<String> response =
                controller.handlePaystackWebhook(payload, "deadbeef-not-a-valid-signature");

        assertEquals(401, response.getStatusCode().value(), "forged signature rejected");

        assertEquals("PENDING", invoiceStatus(reference), "invoice left untouched");
        assertTrue(subscriptionsFor(tenant.getId()).isEmpty(),
                "no subscription created on rejected webhook");
    }

    // ── missing signature → 401 ─────────────────────────────────────────────────
    @Test
    void missingSignature_returns401() {
        String payload = chargeSuccessPayload("TEST-PHASE-B-NOSIG-001", 7_500_000L);
        ResponseEntity<String> response = controller.handlePaystackWebhook(payload, null);
        assertEquals(401, response.getStatusCode().value());
    }

    // ── transfer.reversed routes to the new handler; a no-match reference is a safe no-op (200) ──
    @Test
    void transferReversed_isRouted_andNoMatchReferenceReturns200() throws Exception {
        String payload = "{\"event\":\"transfer.reversed\",\"data\":{\"reference\":\"TEST-PHASE-B-NOMATCH-REV\"}}";
        ResponseEntity<String> response = controller.handlePaystackWebhook(payload, sign(payload));
        assertEquals(200, response.getStatusCode().value(),
                "transfer.reversed is handled (no matching entry → skipped, still 200)");
    }
}
