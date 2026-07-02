package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.common.GlobalExceptionHandler;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link FeatureCheckAspect} enforces {@link RequiresFeature}: tenant is resolved
 * from {@link TenantContext} (never from method arguments), denial throws
 * {@link FeatureNotAvailableException}, and the aspect honours both method- and type-level
 * annotations. The real {@link FeatureFlagService} is used so the TenantContext → UUID →
 * {@link SubscriptionService#hasFeature} resolution path is exercised; only the terminal
 * {@code hasFeature} verdict is mocked.
 */
@ExtendWith(MockitoExtension.class)
class FeatureCheckAspectTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private PlatformFlagService platformFlagService;

    private static final UUID CONTEXT_TENANT = UUID.randomUUID();
    private static final UUID DECOY_ARG_TENANT = UUID.randomUUID();

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(CONTEXT_TENANT.toString());
        // No layered flag check blocks these tests — isEnabledForTenant defaults to true, so
        // the tenant/plan (hasFeature) resolution path is what is under test here. Lenient:
        // the SUPER_ADMIN bypass test short-circuits before the flag is ever consulted.
        lenient().when(platformFlagService.isEnabledForTenant(anyString(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** Wrap a target in a proxy with the real aspect + real FeatureFlagService. */
    private <T> T proxy(T target) {
        FeatureCheckAspect aspect = new FeatureCheckAspect(new FeatureFlagService(subscriptionService, platformFlagService));
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    // ── Method-level annotation, plan lacks feature → 402 exception ────────────
    @Test
    void methodGated_withoutFeature_throwsFeatureNotAvailable() {
        when(subscriptionService.hasFeature(CONTEXT_TENANT, SubscriptionFeature.LEAVE_MANAGEMENT))
                .thenReturn(false);
        GatedService svc = proxy(new GatedService());

        FeatureNotAvailableException ex =
                assertThrows(FeatureNotAvailableException.class, svc::leaveAction);

        assertEquals(SubscriptionFeature.LEAVE_MANAGEMENT, ex.getFeature());
        assertEquals("Upgrade your plan to access LEAVE_MANAGEMENT", ex.getMessage());
    }

    // ── Method-level annotation, plan has feature → method runs normally ───────
    @Test
    void methodGated_withFeature_succeeds() {
        when(subscriptionService.hasFeature(CONTEXT_TENANT, SubscriptionFeature.LEAVE_MANAGEMENT))
                .thenReturn(true);
        GatedService svc = proxy(new GatedService());

        assertEquals("done", svc.leaveAction());
    }

    // ── Tenant comes from TenantContext, NOT from a method argument ────────────
    @Test
    void resolvesTenantFromContext_notFromArgs() {
        when(subscriptionService.hasFeature(CONTEXT_TENANT, SubscriptionFeature.LEAVE_MANAGEMENT))
                .thenReturn(true);
        GatedService svc = proxy(new GatedService());

        // A decoy tenant id is passed as the argument; the aspect must ignore it.
        svc.leaveActionForTenant(DECOY_ARG_TENANT);

        verify(subscriptionService).hasFeature(CONTEXT_TENANT, SubscriptionFeature.LEAVE_MANAGEMENT);
    }

    // ── Type-level annotation gates every method of the bean ──────────────────
    @Test
    void typeLevelAnnotation_gatesAllMethods() {
        when(subscriptionService.hasFeature(CONTEXT_TENANT, SubscriptionFeature.ATTENDANCE_TRACKING))
                .thenReturn(false);
        TypeGatedService svc = proxy(new TypeGatedService());

        assertThrows(FeatureNotAvailableException.class, svc::anyMethod);
    }

    // ── No tenant in context → fail closed (FeatureFlagService.isEnabled false) ─
    @Test
    void noTenantInContext_denies() {
        TenantContext.clear();
        GatedService svc = proxy(new GatedService());

        assertThrows(FeatureNotAvailableException.class, svc::leaveAction);
        // hasFeature is never reached when there is no tenant bound.
        assertFalse(new FeatureFlagService(subscriptionService, platformFlagService).isEnabled(SubscriptionFeature.LEAVE_MANAGEMENT));
    }

    // ── SUPER_ADMIN is exempt: gate is skipped even when the plan lacks the feature ─
    @Test
    void superAdmin_bypassesGate_evenWhenPlanLacksFeature() {
        // Platform owner authenticated. No hasFeature stub: the aspect must short-circuit
        // before ever consulting the subscription (strict stubs would flag an unused stub).
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "owner@platform.io", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        GatedService svc = proxy(new GatedService());

        assertEquals("done", svc.leaveAction());
    }

    // ── A non-SUPER_ADMIN principal is still gated normally ────────────────────
    @Test
    void nonSuperAdmin_withoutFeature_stillThrows() {
        when(subscriptionService.hasFeature(CONTEXT_TENANT, SubscriptionFeature.LEAVE_MANAGEMENT))
                .thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "hr@tenant.io", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_HR_ADMIN"))));
        GatedService svc = proxy(new GatedService());

        assertThrows(FeatureNotAvailableException.class, svc::leaveAction);
    }

    // ── 402 mapping in the global handler ──────────────────────────────────────
    @Test
    void handlerMapsFeatureNotAvailableTo402() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleFeatureNotAvailable(
                new FeatureNotAvailableException(SubscriptionFeature.CUSTOM_REPORTING));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Upgrade your plan to access CUSTOM_REPORTING", response.getBody().getError());
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    /** Method-level gating. */
    static class GatedService {
        @RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
        String leaveAction() {
            return "done";
        }

        @RequiresFeature(SubscriptionFeature.LEAVE_MANAGEMENT)
        String leaveActionForTenant(UUID tenantId) {
            return "done:" + tenantId;
        }
    }

    /** Type-level gating: the annotation on the class applies to every method. */
    @RequiresFeature(SubscriptionFeature.ATTENDANCE_TRACKING)
    static class TypeGatedService {
        String anyMethod() {
            return "done";
        }
    }
}
