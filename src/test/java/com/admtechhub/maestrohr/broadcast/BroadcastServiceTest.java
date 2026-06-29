package com.admtechhub.maestrohr.broadcast;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BroadcastServiceTest {

    @Mock private BroadcastRepository broadcastRepository;
    @Mock private BroadcastReadRepository broadcastReadRepository;
    @Mock private TenantRepository tenantRepository;

    @InjectMocks private BroadcastService service;

    private static final String EMAIL = "user@acme.com";
    private static final UUID TENANT_ID = UUID.randomUUID();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private Broadcast broadcast(String targetPlans) {
        Broadcast b = new Broadcast();
        b.setTitle("Test Title");
        b.setBody("Test Body");
        b.setTargetPlans(targetPlans);
        b.setCreatedBy("admin@maestro.com");
        return b;
    }

    @Test
    void createBroadcast_targetAll_savedCorrectly() {
        Broadcast saved = broadcast("ALL");
        when(broadcastRepository.save(any(Broadcast.class))).thenReturn(saved);

        Broadcast result = service.create("Test Title", "Test Body", "ALL", "admin@maestro.com");

        assertThat(result.getTargetPlans()).isEqualTo("ALL");
        assertThat(result.getCreatedBy()).isEqualTo("admin@maestro.com");
        verify(broadcastRepository).save(any(Broadcast.class));
    }

    @Test
    void unreadFor_planMatches_returnsBroadcast() {
        TenantContext.setCurrentTenant(TENANT_ID.toString());
        Tenant tenant = new Tenant();
        tenant.setSubscriptionPlan(SubscriptionPlan.BASIC);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        Broadcast b = broadcast("BASIC");
        b.setCreatedAt(OffsetDateTime.now());
        when(broadcastRepository.findUnreadFor(EMAIL)).thenReturn(List.of(b));

        List<BroadcastService.UnreadBroadcast> result = service.unreadFor(EMAIL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Test Title");
    }

    @Test
    void unreadFor_planMismatch_returnsEmpty() {
        TenantContext.setCurrentTenant(TENANT_ID.toString());
        Tenant tenant = new Tenant();
        tenant.setSubscriptionPlan(SubscriptionPlan.BASIC);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        Broadcast b = broadcast("PROFESSIONAL");
        b.setCreatedAt(OffsetDateTime.now());
        when(broadcastRepository.findUnreadFor(EMAIL)).thenReturn(List.of(b));

        List<BroadcastService.UnreadBroadcast> result = service.unreadFor(EMAIL);

        assertThat(result).isEmpty();
    }

    @Test
    void markRead_idempotent_noDuplicates() {
        UUID broadcastId = UUID.randomUUID();
        when(broadcastReadRepository.existsByBroadcastIdAndUserEmail(broadcastId, EMAIL)).thenReturn(true);

        service.markRead(broadcastId, EMAIL);

        verify(broadcastReadRepository, never()).save(any());
    }

    @Test
    void unreadFor_alreadyRead_returnsEmpty() {
        TenantContext.setCurrentTenant(TENANT_ID.toString());
        Tenant tenant = new Tenant();
        tenant.setSubscriptionPlan(SubscriptionPlan.BASIC);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        when(broadcastRepository.findUnreadFor(EMAIL)).thenReturn(List.of());

        List<BroadcastService.UnreadBroadcast> result = service.unreadFor(EMAIL);

        assertThat(result).isEmpty();
    }
}
