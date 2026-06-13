package com.admtechhub.maestrohr.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the single source of truth for exempt paths. These assertions pin the grouping
 * semantics that the four consumers (SecurityConfig, TenantValidationFilter,
 * LapsedAccessFilter, JwtAuthFilter) now depend on.
 */
class PublicPathsTest {

    // ── NO_TENANT + DOCS are exempt from tenant enforcement ───────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/login",
            "/api/webhooks/paystack",
            "/api/pricing/public",
            "/actuator/health",
            "/actuator/info",
            "/css/app.css",
            "/js/app.js",
            "/images/logo.png",
            "/",
            "/login",
            "/register",
            "/dashboard",
            "/swagger-ui/index.html",
            "/v3/api-docs",
            "/favicon.ico"
    })
    void isNoTenant_trueForPublicAndDocsPaths(String path) {
        assertTrue(PublicPaths.isNoTenant(path), path + " should require no tenant context");
    }

    // ── UI_SHELL routes are NOT no-tenant: they require a token (known issue) ──
    @ParameterizedTest
    @ValueSource(strings = {
            "/employees/list",
            "/departments",
            "/pay-grades",
            "/payroll/run",
            "/leave/requests",
            "/attendance",
            "/reports/payroll",
            "/subscription"
    })
    void isNoTenant_falseForUiShellRoutes(String path) {
        assertFalse(PublicPaths.isNoTenant(path),
                path + " is permitAll but must still require a tenant context");
    }

    // ── Genuine business API paths are not exempt ─────────────────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/employees",
            "/api/payroll/run",
            "/api/leave",
            "/api/payment/initialize"  // payment requires auth; only freeze-exempt, not public
    })
    void isNoTenant_falseForProtectedApiPaths(String path) {
        assertFalse(PublicPaths.isNoTenant(path), path + " must require a tenant context");
    }

    // ── WRITE_EXEMPT: freeze bypass ───────────────────────────────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/payment/initialize",
            "/api/payment/oauth/callback",
            "/api/webhooks/paystack",
            "/api/auth/login"
    })
    void isWriteExempt_trueForReactivationPaths(String path) {
        assertTrue(PublicPaths.isWriteExempt(path), path + " should bypass the read-only freeze");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/employees", "/api/payroll/run", "/dashboard"})
    void isWriteExempt_falseForBusinessResources(String path) {
        assertFalse(PublicPaths.isWriteExempt(path), path + " must be subject to the freeze");
    }

    // ── permitAll = NO_TENANT ∪ UI_SHELL (and excludes DOCS, preserving prior behavior) ──
    @Test
    void permitAllPatterns_isUnionOfNoTenantAndUiShell() {
        List<String> permitAll = Arrays.asList(PublicPaths.permitAllPatterns());

        assertTrue(permitAll.containsAll(PublicPaths.NO_TENANT), "permitAll must include NO_TENANT");
        assertTrue(permitAll.containsAll(PublicPaths.UI_SHELL), "permitAll must include UI_SHELL");
        // DOCS were never permitAll before consolidation — keep it that way.
        assertFalse(permitAll.contains("/swagger-ui/**"), "DOCS must not be permitAll");
        assertFalse(permitAll.contains("/v3/api-docs/**"), "DOCS must not be permitAll");
    }
}
