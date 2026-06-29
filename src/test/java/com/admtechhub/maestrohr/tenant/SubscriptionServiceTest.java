package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.admtechhub.maestrohr.subscription.TenantSubscription;
import com.admtechhub.maestrohr.subscription.TenantSubscriptionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    @Mock TenantRepository              tenantRepository;
    @Mock TenantSubscriptionRepository  tenantSubscriptionRepository;
    @Mock PricingService                pricingService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) EntityManager entityManager;

    @InjectMocks SubscriptionService subscriptionService;

    private UUID tenantId;
    private TenantSubscription sub;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        sub = mock(TenantSubscription.class);
    }

    // ── hasFeature ────────────────────────────────────────────────────────────

    @Test
    void hasFeature_tenantOnProfessional_leaveManagementTrue() {
        when(sub.getStatus()).thenReturn(SubscriptionStatus.ACTIVE);
        when(sub.getCurrentPeriodEnd()).thenReturn(OffsetDateTime.now().plusDays(30));
        when(sub.getPlan()).thenReturn(SubscriptionPlan.PROFESSIONAL);
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(sub));

        assertTrue(subscriptionService.hasFeature(tenantId, SubscriptionFeature.LEAVE_MANAGEMENT));
    }

    @Test
    void hasFeature_tenantOnFreeTrial_leaveManagementFalse() {
        when(sub.getStatus()).thenReturn(SubscriptionStatus.TRIALING);
        when(sub.getCurrentPeriodEnd()).thenReturn(OffsetDateTime.now().plusDays(7));
        when(sub.getPlan()).thenReturn(SubscriptionPlan.FREE_TRIAL);
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(sub));

        assertFalse(subscriptionService.hasFeature(tenantId, SubscriptionFeature.LEAVE_MANAGEMENT));
    }

    @Test
    void hasFeature_tenantOnEnterprise_allFeaturesTrue() {
        when(sub.getStatus()).thenReturn(SubscriptionStatus.ACTIVE);
        when(sub.getCurrentPeriodEnd()).thenReturn(OffsetDateTime.now().plusDays(365));
        when(sub.getPlan()).thenReturn(SubscriptionPlan.ENTERPRISE);
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(sub));

        for (SubscriptionFeature feature : new SubscriptionFeature[]{
                SubscriptionFeature.LEAVE_MANAGEMENT,
                SubscriptionFeature.LOAN_MANAGEMENT,
                SubscriptionFeature.ATTENDANCE_TRACKING,
                SubscriptionFeature.CUSTOM_REPORTING,
                SubscriptionFeature.DOCUMENT_VAULT,
                SubscriptionFeature.ADVANCED_PAYROLL
        }) {
            assertTrue(subscriptionService.hasFeature(tenantId, feature),
                    "ENTERPRISE must include feature: " + feature);
        }
    }

    // ── isTrialing (via getStatus) ────────────────────────────────────────────

    @Test
    void isTrialing_trialing_returnsTrue() {
        when(sub.getStatus()).thenReturn(SubscriptionStatus.TRIALING);
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(sub));

        assertEquals(SubscriptionStatus.TRIALING, subscriptionService.getStatus(tenantId));
    }

    @Test
    void isTrialing_active_returnsFalse() {
        when(sub.getStatus()).thenReturn(SubscriptionStatus.ACTIVE);
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(sub));

        assertNotEquals(SubscriptionStatus.TRIALING, subscriptionService.getStatus(tenantId));
    }

    // ── isExpired ────────────────────────────────────────────────────────────

    @Test
    void isExpired_pastDueDate_returnsTrue() {
        when(sub.getStatus()).thenReturn(SubscriptionStatus.ACTIVE);
        when(sub.getCurrentPeriodEnd()).thenReturn(OffsetDateTime.now().minusDays(1));
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(sub));
        when(tenantSubscriptionRepository.save(any())).thenReturn(sub);

        boolean lapsed = subscriptionService.lapseExpired(tenantId);

        assertTrue(lapsed, "lapseExpired must return true when period has elapsed");
        verify(sub).setStatus(SubscriptionStatus.EXPIRED);
        verify(tenantSubscriptionRepository).save(sub);
    }
}
