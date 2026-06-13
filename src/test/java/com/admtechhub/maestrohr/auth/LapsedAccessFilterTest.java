package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LapsedAccessFilter}: an EXPIRED tenant is frozen to read-only
 * (writes → 402) but may still read, pay, and reactivate; ACTIVE tenants pass freely.
 */
@ExtendWith(MockitoExtension.class)
class LapsedAccessFilterTest {

    @Mock private SubscriptionService subscriptionService;
    @Mock private FilterChain chain;

    private static final String TENANT_ID = UUID.randomUUID().toString();

    private LapsedAccessFilter filter() {
        return new LapsedAccessFilter(subscriptionService);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod(method);
        req.setRequestURI(uri);
        return req;
    }

    // ── EXPIRED tenant: GET passes through (200, no block) ────────────────────
    @Test
    void expiredTenant_getRequest_passesThrough() throws Exception {
        TenantContext.setCurrentTenant(TENANT_ID);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(request("GET", "/api/employees"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
        // Safe reads short-circuit before any subscription lookup.
        verifyNoInteractions(subscriptionService);
    }

    // ── EXPIRED tenant: POST to a business resource → 402 ─────────────────────
    @Test
    void expiredTenant_postBusinessResource_returns402() throws Exception {
        TenantContext.setCurrentTenant(TENANT_ID);
        when(subscriptionService.getStatus(UUID.fromString(TENANT_ID)))
                .thenReturn(SubscriptionStatus.EXPIRED);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(request("POST", "/api/employees"), res, chain);

        assertEquals(402, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    // ── EXPIRED tenant: can still hit payment-initialize to reactivate ────────
    @Test
    void expiredTenant_paymentInitialize_isAllowed() throws Exception {
        TenantContext.setCurrentTenant(TENANT_ID);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(request("POST", "/api/payment/initialize"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
        // Exempt path short-circuits before any subscription lookup.
        verifyNoInteractions(subscriptionService);
    }

    // ── ACTIVE tenant: write passes through unaffected ────────────────────────
    @Test
    void activeTenant_postBusinessResource_passesThrough() throws Exception {
        TenantContext.setCurrentTenant(TENANT_ID);
        when(subscriptionService.getStatus(UUID.fromString(TENANT_ID)))
                .thenReturn(SubscriptionStatus.ACTIVE);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(request("POST", "/api/employees"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
    }
}
