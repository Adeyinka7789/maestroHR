package com.admtechhub.maestrohr.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TenantValidationFilter}: paths exempt via {@link PublicPaths#isNoTenant}
 * pass through without a tenant context; every other path requires one (else 403).
 */
@ExtendWith(MockitoExtension.class)
class TenantValidationFilterTest {

    @Mock private FilterChain chain;

    private final TenantValidationFilter filter = new TenantValidationFilter();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        // Explicitly set Accept header to application/json to ensure 403 response
        // instead of redirect to /login (which happens for text/html Accept header)
        req.addHeader("Accept", "application/json");
        return req;
    }

    // ── Exempt paths pass through even with no tenant context ─────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/login",
            "/api/webhooks/paystack",
            "/api/pricing/public",
            "/actuator/health",
            "/css/app.css",
            "/login",
            "/dashboard",
            "/swagger-ui/index.html"
    })
    void exemptPath_passesThrough_withoutTenant(String path) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request(path), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
    }

    // ── A protected path with no tenant context → 403 ─────────────────────────
    @Test
    void protectedPath_withoutTenant_returns403() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("/api/employees"), res, chain);

        // Verify we get 403 for API request (with Accept: application/json header)
        assertEquals(403, res.getStatus(),
                "Protected path without tenant must return 403 for API requests");
        verify(chain, never()).doFilter(any(), any());
    }

    // ── Test HTML browser request gets redirected to login ───────────────────
    @Test
    void protectedPath_withoutTenant_htmlRequest_redirectsToLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/employees");
        req.addHeader("Accept", "text/html");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(302, res.getStatus(),
                "Browser request without tenant should redirect to /login");
        assertEquals("/login", res.getRedirectedUrl());
        verify(chain, never()).doFilter(any(), any());
    }

    // ── A protected path WITH a tenant context passes through ─────────────────
    @Test
    void protectedPath_withTenant_passesThrough() throws Exception {
        TenantContext.setCurrentTenant(UUID.randomUUID().toString());
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("/api/employees"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
    }

    // ── §3(a) known issue: UI_SHELL routes are permitAll but still tenant-gated.
    //    Pins current behavior — an unauthenticated shell request is 403'd here. ──
    @ParameterizedTest
    @ValueSource(strings = {"/employees/list", "/payroll/run", "/reports/payroll", "/subscription"})
    void uiShellRoute_withoutTenant_returns403_knownIssue(String path) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request(path), res, chain);

        assertEquals(403, res.getStatus(),
                "UI shell route without tenant must return 403 for API requests");
        verify(chain, never()).doFilter(any(), any());
    }
}