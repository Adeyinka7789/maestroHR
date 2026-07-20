package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.dto.PaymentInitializeRequest;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.tenant.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises a DISCOUNTED checkout end-to-end against the real database: the discount is created
 * in {@code discounts} (V58), resolved server-side by {@link DiscountService}, and applied by
 * {@link PaymentService}. Only the outbound Paystack HTTP call is mocked. Companion to
 * {@link PaymentControllerInitializeTest} (which covers the no-discount path).
 */
@SpringBootTest
@Transactional
class DiscountedCheckoutIntegrationTest {

    @Autowired private PaymentController paymentController;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private DiscountRepository discountRepository;
    @Autowired private PricingService pricingService;
    @Autowired private EntityManager entityManager;

    @MockBean private PaystackClient paystackClient;

    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    private UUID newTenant() {
        Tenant tenant = tenantRepository.saveAndFlush(Tenant.builder()
                .companyName("TEST-DISCOUNT " + UUID.randomUUID())
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .paymentPeriod(PaymentPeriod.MONTHLY)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build());
        TenantContext.setCurrentTenant(tenant.getId().toString());
        bindTenant(tenant.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("billing@test.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_HR_ADMIN"))));
        return tenant.getId();
    }

    private void saveDiscount(UUID tenantId, DiscountType type, Integer bps, Long kobo,
                              String plan, String period) {
        discountRepository.saveAndFlush(Discount.builder()
                .label(type == DiscountType.PERCENTAGE ? (bps / 100) + "% off" : "flat off")
                .discountType(type)
                .percentBps(bps)
                .amountKobo(kobo)
                .tenantId(tenantId)
                .planName(plan)
                .period(period)
                .isActive(true)
                .build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void percentageDiscount_quotesAndChargesNet_andRecordsDiscountOnInvoice() {
        UUID tenantId = newTenant();
        long base = pricingService.getPrice("PROFESSIONAL", "MONTHLY");
        assertThat(base).isPositive();
        saveDiscount(tenantId, DiscountType.PERCENTAGE, 2000, null, "PROFESSIONAL", "MONTHLY"); // 20%

        long expectedNet = base - (base * 2000 / 10_000);

        // Quote reflects the discount.
        ResponseEntity<ApiResponse<Map<String, Object>>> quote =
                paymentController.quote(SubscriptionPlan.PROFESSIONAL, PaymentPeriod.MONTHLY);
        Map<String, Object> q = quote.getBody().getData();
        assertThat(((Number) q.get("baseKobo")).longValue()).isEqualTo(base);
        assertThat(((Number) q.get("netKobo")).longValue()).isEqualTo(expectedNet);
        assertThat(((Number) q.get("discountKobo")).longValue()).isEqualTo(base - expectedNet);

        // Initialize charges the NET amount via Paystack.
        when(paystackClient.initializeTransaction(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(PaystackResponse.Data.builder().authorizationUrl("https://checkout.paystack.com/x").build());

        PaymentInitializeRequest req = new PaymentInitializeRequest();
        req.setPlan("PROFESSIONAL");
        req.setPeriod("MONTHLY");
        req.setAmount(999L); // tampered — must be ignored

        ResponseEntity<ApiResponse<Map<String, String>>> resp =
                paymentController.initializePayment(req, SecurityContextHolder.getContext().getAuthentication());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().get("authorization_url")).isEqualTo("https://checkout.paystack.com/x");

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(paystackClient).initializeTransaction(anyString(), amount.capture(), anyString(), anyString());
        assertThat(amount.getValue()).isEqualTo(expectedNet);

        // getOrCreatePendingInvoice is self-invoked, so its REQUIRES_NEW is bypassed and the write
        // lives in THIS transaction — the query below autoflushes and sees it.
        Invoice invoice = invoiceRepository.findByPaystackReference(
                "SUB_" + tenantId.toString().substring(0, 8) + "_PROFESSIONAL_MONTHLY").orElseThrow();
        assertThat(invoice.getAmountKobo()).isEqualTo(expectedNet);
        assertThat(invoice.getDiscountKobo()).isEqualTo(base - expectedNet);
        assertThat(invoice.getDiscountLabel()).isEqualTo("20% off");
        assertThat(invoice.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void fullDiscount_activatesFreeWithoutPaystack_andUpgradesPlan() {
        UUID tenantId = newTenant();
        long base = pricingService.getPrice("PROFESSIONAL", "ANNUALLY");
        assertThat(base).isPositive();
        saveDiscount(tenantId, DiscountType.PERCENTAGE, 10_000, null, "PROFESSIONAL", "ANNUALLY"); // 100%

        PaymentInitializeRequest req = new PaymentInitializeRequest();
        req.setPlan("PROFESSIONAL");
        req.setPeriod("ANNUALLY");
        req.setAmount(base);

        ResponseEntity<ApiResponse<Map<String, String>>> resp =
                paymentController.initializePayment(req, SecurityContextHolder.getContext().getAuthentication());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData()).containsKey("redirect");
        assertThat(resp.getBody().getData()).doesNotContainKey("authorization_url");

        // No charge attempted for a ₦0 checkout.
        verify(paystackClient, never()).initializeTransaction(anyString(), anyLong(), anyString(), anyString());

        // Self-invoked activateFreeSubscription + upgradePlan run in THIS transaction; the invoice
        // and tenant changes are visible to same-tx reads below.
        Invoice invoice = invoiceRepository.findByPaystackReference(
                "SUB_" + tenantId.toString().substring(0, 8) + "_PROFESSIONAL_ANNUALLY").orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(invoice.getAmountKobo()).isZero();
        assertThat(invoice.getDiscountKobo()).isEqualTo(base);

        Tenant upgraded = tenantRepository.findById(tenantId).orElseThrow();
        assertThat(upgraded.getSubscriptionPlan()).isEqualTo(SubscriptionPlan.PROFESSIONAL);
        assertThat(upgraded.getPaymentPeriod()).isEqualTo(PaymentPeriod.ANNUALLY);
        assertThat(upgraded.isActive()).isTrue();
    }
}
