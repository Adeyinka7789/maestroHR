package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscriptionLapseJob}: candidate selection, per-tenant
 * {@link TenantContext} handling, and resilience to a single tenant failing.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionLapseJobTest {

    @Mock private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Mock private SubscriptionService subscriptionService;

    @InjectMocks private SubscriptionLapseJob job;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void lapsesEachExpiredCandidate() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(tenantSubscriptionRepository.findExpiredActiveTenantIds())
                .thenReturn(List.of(t1, t2));
        when(subscriptionService.lapseExpired(any())).thenReturn(true);

        job.lapseExpiredSubscriptions();

        verify(subscriptionService).lapseExpired(t1);
        verify(subscriptionService).lapseExpired(t2);
    }

    @Test
    void noCandidates_doesNothing() {
        when(tenantSubscriptionRepository.findExpiredActiveTenantIds())
                .thenReturn(List.of());

        job.lapseExpiredSubscriptions();

        verify(subscriptionService, never()).lapseExpired(any());
    }

    @Test
    void bindsCorrectTenantContextPerIteration() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(tenantSubscriptionRepository.findExpiredActiveTenantIds())
                .thenReturn(List.of(t1, t2));
        // Assert the context that is active at the moment each tenant is processed.
        when(subscriptionService.lapseExpired(t1)).thenAnswer(inv -> {
            assert TenantContext.getCurrentTenant().equals(t1.toString())
                    : "tenant context must be bound to t1 while processing t1";
            return true;
        });
        when(subscriptionService.lapseExpired(t2)).thenAnswer(inv -> {
            assert TenantContext.getCurrentTenant().equals(t2.toString())
                    : "tenant context must be bound to t2 while processing t2";
            return true;
        });

        job.lapseExpiredSubscriptions();

        // Context is cleared after the loop completes — no leakage to the caller thread.
        assertNull(TenantContext.getCurrentTenant(),
                "TenantContext must be cleared after the sweep");
    }

    @Test
    void oneTenantFailing_doesNotAbortTheRest() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(tenantSubscriptionRepository.findExpiredActiveTenantIds())
                .thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("boom")).when(subscriptionService).lapseExpired(failing);
        when(subscriptionService.lapseExpired(ok)).thenReturn(true);

        job.lapseExpiredSubscriptions();

        // The second tenant is still processed despite the first throwing.
        verify(subscriptionService).lapseExpired(ok);
        // And context is cleared even when an iteration throws.
        assertNull(TenantContext.getCurrentTenant(),
                "TenantContext must be cleared after a failing iteration");
    }
}
