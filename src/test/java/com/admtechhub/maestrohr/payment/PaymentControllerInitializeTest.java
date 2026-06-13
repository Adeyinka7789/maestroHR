package com.admtechhub.maestrohr.payment;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.payment.dto.PaymentInitializeRequest;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.PricingService;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Phase B: payment-initialize must resolve the price server-side (kobo) from
 * pricing_config and create a PENDING invoice — never trusting the client-supplied
 * amount. Paystack's HTTP call is mocked; the focus is the invoice we persist.
 */
@SpringBootTest
@Transactional
class PaymentControllerInitializeTest {

    @Autowired private PaymentController paymentController;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private PricingService pricingService;
    @Autowired private EntityManager entityManager;

    @MockBean private PaystackClient paystackClient;

    private void bindTenant(UUID tenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
                .setParameter("tid", tenantId.toString())
                .getSingleResult();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void initialize_resolvesKoboServerSide_ignoringClientAmount_andCreatesPendingInvoice() {
        Tenant tenant = tenantRepository.saveAndFlush(Tenant.builder()
                .companyName("TEST-PHASE-B Initialize Tenant")
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .paymentPeriod(PaymentPeriod.MONTHLY)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .build());

        TenantContext.setCurrentTenant(tenant.getId().toString());
        bindTenant(tenant.getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "billing@test.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_HR_ADMIN"))));

        when(paystackClient.initializeTransaction(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(PaystackResponse.Data.builder()
                        .authorizationUrl("https://checkout.paystack.com/test-auth")
                        .build());

        long expectedKobo = pricingService.getPrice(
                SubscriptionPlan.PROFESSIONAL.name(), PaymentPeriod.MONTHLY.name());

        PaymentInitializeRequest request = new PaymentInitializeRequest();
        request.setPlan("PROFESSIONAL");
        request.setPeriod("MONTHLY");
        request.setAmount(999L); // tampered client amount — must be ignored

        ResponseEntity<ApiResponse<Map<String, String>>> response =
                paymentController.initializePayment(request, SecurityContextHolder.getContext().getAuthentication());

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("https://checkout.paystack.com/test-auth",
                response.getBody().getData().get("authorization_url"));

        bindTenant(tenant.getId());
        List<Invoice> invoices = invoiceRepository.findAll();
        assertEquals(1, invoices.size(), "exactly one PENDING invoice created");

        Invoice invoice = invoices.get(0);
        assertEquals(PaymentStatus.PENDING, invoice.getStatus());
        assertEquals(SubscriptionPlan.PROFESSIONAL, invoice.getPlan());
        assertEquals(PaymentPeriod.MONTHLY, invoice.getPeriod());
        assertEquals(expectedKobo, invoice.getAmountKobo(),
                "amount comes from pricing_config (kobo), not the request");
        assertNotEquals(999L, invoice.getAmountKobo(), "client-supplied amount must be ignored");
    }
}
