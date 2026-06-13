package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.PaymentPeriod;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscriptionService#hasFeature}, which reads from
 * {@link TenantSubscription} and must fail closed.
 *
 * <p>All collaborators are mocked. {@code EntityManager} is stubbed only to satisfy
 * the internal {@code bindTenantSession} call (a transaction-local {@code set_config});
 * its effect — Postgres session scoping for {@code @SQLRestriction} — is a database
 * behaviour exercised by the repository integration tests, not here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Mock private com.admtechhub.maestrohr.tenant.PricingService pricingService;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    @InjectMocks private SubscriptionService subscriptionService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void stubTenantSessionBinding() {
        // bindTenantSession runs: SELECT set_config('app.current_tenant', :tid, true)
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn("");
    }

    // ── Missing subscription → fail closed ────────────────────────────────────
    @Test
    void missingSubscription_returnsFalse() {
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.empty());

        assertFalse(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.BASIC_PAYROLL),
                "no TenantSubscription row must deny access");
    }

    // ── Null currentPeriodEnd → fail closed ───────────────────────────────────
    @Test
    void nullCurrentPeriodEnd_returnsFalse() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(null)
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertFalse(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.LEAVE_MANAGEMENT),
                "a null period end must deny access even when status is ACTIVE");
    }

    // ── Expired by date (status ACTIVE, period end in the past) → false ───────
    @Test
    void expiredByDate_returnsFalse() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(OffsetDateTime.now().minusDays(1))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertFalse(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.LEAVE_MANAGEMENT),
                "a lapsed period end must deny access");
    }

    // ── EXPIRED status (even if the date were future) → false ─────────────────
    @Test
    void expiredStatus_returnsFalse() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.EXPIRED)
                .currentPeriodEnd(OffsetDateTime.now().plusDays(30))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertFalse(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.LEAVE_MANAGEMENT),
                "EXPIRED status must deny access regardless of the period end date");
    }

    // ── PAST_DUE status → false ───────────────────────────────────────────────
    @Test
    void pastDueStatus_returnsFalse() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.PAST_DUE)
                .currentPeriodEnd(OffsetDateTime.now().plusDays(30))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertFalse(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.LEAVE_MANAGEMENT),
                "PAST_DUE status must deny access");
    }

    // ── Active subscription whose plan includes the feature → true ────────────
    @Test
    void activeSubscriptionWithFeature_returnsTrue() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(OffsetDateTime.now().plusDays(30))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        // PROFESSIONAL includes LEAVE_MANAGEMENT (see SubscriptionPlan enum).
        assertTrue(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.LEAVE_MANAGEMENT),
                "an active subscription must grant a feature its plan includes");
    }

    // ── Active subscription whose plan lacks the feature → false ──────────────
    @Test
    void activeSubscriptionWithoutFeature_returnsFalse() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.BASIC)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(OffsetDateTime.now().plusDays(30))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        // BASIC does NOT include LEAVE_MANAGEMENT.
        assertFalse(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.LEAVE_MANAGEMENT),
                "an active subscription must deny a feature its plan does not include");
    }

    // ── TRIALING within the period → true for trial-tier features ─────────────
    @Test
    void trialingWithinPeriod_returnsTrueForTrialFeature() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.FREE_TRIAL)
                .status(SubscriptionStatus.TRIALING)
                .currentPeriodEnd(OffsetDateTime.now().plusDays(15))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertTrue(subscriptionService.hasFeature(TENANT_ID, SubscriptionFeature.BASIC_PAYROLL),
                "a live trial must grant features included in the trial plan");
    }

    // ── lapseExpired: ACTIVE + past period end → flips to EXPIRED ──────────────
    @Test
    void lapseExpired_activeAndPast_flipsToExpired() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(OffsetDateTime.now().minusDays(1))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        boolean lapsed = subscriptionService.lapseExpired(TENANT_ID);

        assertTrue(lapsed, "an expired ACTIVE subscription must be lapsed");
        assertEquals(SubscriptionStatus.EXPIRED, sub.getStatus());
        verify(tenantSubscriptionRepository).save(sub);
    }

    // ── lapseExpired: ACTIVE but still within period → untouched ──────────────
    @Test
    void lapseExpired_activeAndFuture_isNoOp() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(OffsetDateTime.now().plusDays(10))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        boolean lapsed = subscriptionService.lapseExpired(TENANT_ID);

        assertFalse(lapsed, "a subscription still within its period must not lapse");
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        verify(tenantSubscriptionRepository, never()).save(any());
    }

    // ── lapseExpired: already EXPIRED → no double-processing ──────────────────
    @Test
    void lapseExpired_notActive_isNoOp() {
        TenantSubscription sub = TenantSubscription.builder()
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.EXPIRED)
                .currentPeriodEnd(OffsetDateTime.now().minusDays(5))
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertFalse(subscriptionService.lapseExpired(TENANT_ID),
                "a non-ACTIVE subscription must not be re-processed");
        verify(tenantSubscriptionRepository, never()).save(any());
    }

    // ── getStatus: returns the row's status, or null when no row ──────────────
    @Test
    void getStatus_returnsStatus() {
        TenantSubscription sub = TenantSubscription.builder()
                .status(SubscriptionStatus.EXPIRED)
                .build();
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(sub));

        assertEquals(SubscriptionStatus.EXPIRED, subscriptionService.getStatus(TENANT_ID));
    }

    @Test
    void getStatus_noSubscription_returnsNull() {
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.empty());

        assertNull(subscriptionService.getStatus(TENANT_ID),
                "a tenant with no subscription row has no status");
    }

    // ── Reactivation: a successful payment flips EXPIRED back to ACTIVE ────────
    @Test
    void syncSubscriptionState_reactivatesExpiredToActive() {
        Tenant tenant = Tenant.builder()
                .companyName("ACME")
                .industry("TEST")
                .companySize("1-10")
                .subscriptionPlan(SubscriptionPlan.PROFESSIONAL)
                .paymentPeriod(PaymentPeriod.MONTHLY)
                .subscriptionExpiresAt(OffsetDateTime.now().plusMonths(1))
                .build();
        tenant.setId(TENANT_ID); // syncSubscription looks the row up by tenant.getId()
        TenantSubscription expiredSub = TenantSubscription.builder()
                .tenant(tenant)
                .plan(SubscriptionPlan.PROFESSIONAL)
                .status(SubscriptionStatus.EXPIRED)
                .currentPeriodEnd(OffsetDateTime.now().minusDays(2))
                .build();

        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(tenantSubscriptionRepository.findByTenantId(TENANT_ID))
                .thenReturn(Optional.of(expiredSub));
        when(pricingService.getPrice(anyString(), anyString())).thenReturn(75_000L);

        subscriptionService.syncSubscriptionState(TENANT_ID, SubscriptionStatus.ACTIVE);

        ArgumentCaptor<TenantSubscription> captor = ArgumentCaptor.forClass(TenantSubscription.class);
        verify(tenantSubscriptionRepository).save(captor.capture());
        assertEquals(SubscriptionStatus.ACTIVE, captor.getValue().getStatus(),
                "a charge.success (ACTIVE sync) on a lapsed tenant must restore ACTIVE status");
    }
}
