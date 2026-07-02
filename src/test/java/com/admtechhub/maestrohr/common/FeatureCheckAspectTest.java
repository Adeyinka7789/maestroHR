package com.admtechhub.maestrohr.common;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.subscription.FeatureCheckAspect;
import com.admtechhub.maestrohr.subscription.FeatureFlagService;
import com.admtechhub.maestrohr.subscription.FeatureNotAvailableException;
import com.admtechhub.maestrohr.subscription.PlatformFlagService;
import com.admtechhub.maestrohr.subscription.RequiresFeature;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies the four critical {@link FeatureCheckAspect} contracts.
 * Uses {@link AspectJProxyFactory} (no Spring context) so tests remain fast and isolated.
 * The real {@link FeatureFlagService} is wired so the full TenantContext → plan → flag
 * resolution path is covered; only the terminal collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class FeatureCheckAspectTest {

    @Mock SubscriptionService subscriptionService;
    @Mock PlatformFlagService platformFlagService;

    static final UUID TENANT = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(TENANT.toString());
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private GatedStub proxied() {
        FeatureCheckAspect aspect = new FeatureCheckAspect(
                new FeatureFlagService(subscriptionService, platformFlagService));
        AspectJProxyFactory factory = new AspectJProxyFactory(new GatedStub());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    // ── 12: feature enabled by plan → method proceeds ────────────────────────

    @Test
    void featureEnabled_planIncludes_proceeds() {
        when(platformFlagService.isEnabledForTenant(anyString(), any(), any())).thenReturn(true);
        when(subscriptionService.hasFeature(TENANT, SubscriptionFeature.LEAVE_MANAGEMENT))
                .thenReturn(true);

        assertDoesNotThrow(() -> proxied().leaveOp());
    }

    // ── 13: plan lacks feature → 402 ─────────────────────────────────────────

    @Test
    void featureDisabled_planExcludes_throws402() {
        when(platformFlagService.isEnabledForTenant(anyString(), any(), any())).thenReturn(true);
        when(subscriptionService.hasFeature(TENANT, SubscriptionFeature.LEAVE_MANAGEMENT))
                .thenReturn(false);

        assertThrows(FeatureNotAvailableException.class, () -> proxied().leaveOp());
    }

    // ── 14: SUPER_ADMIN bypasses the check entirely ───────────────────────────

    @Test
    void superAdmin_alwaysBypasses_anyFeature() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "owner@platform.io", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        assertDoesNotThrow(() -> proxied().leaveOp());
        // Subscription service must never be consulted for platform owners.
        verify(subscriptionService, never()).hasFeature(any(), any());
    }

    // ── 15: global platform kill-switch off → 402, subscription not consulted ─

    @Test
    void globalFlagDisabled_throws402() {
        when(platformFlagService.isEnabledForTenant(eq(SubscriptionFeature.LEAVE_MANAGEMENT.name()), any(), any()))
                .thenReturn(false);

        assertThrows(FeatureNotAvailableException.class, () -> proxied().leaveOp());
        // The global flag short-circuits before the per-tenant check.
        verify(subscriptionService, never()).hasFeature(any(), any());
    }

    // ── stub target ───────────────────────────────────────────────────────────

    static class GatedStub {
        @RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
        void leaveOp() { /* no-op body */ }
    }
}
