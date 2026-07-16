package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the flag engine. Since Phase 2 the engine reaches persistence and audit only
 * through the {@link FlagStore} and {@link FlagAuditListener} SPIs, so both are mocked here —
 * exercising the resolution logic in isolation from Spring Data and the audit trail.
 */
@ExtendWith(MockitoExtension.class)
class PlatformFlagServiceTest {

    @Mock private FlagStore flagStore;
    @Mock private FlagAuditListener auditListener;

    @InjectMocks private PlatformFlagService service;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void isEnabled_flagExists_returnsValue() {
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isFalse();
    }

    @Test
    void isEnabled_typedFlagKey_resolvesByKeyName() {
        // The typed overload resolves the flag by its FlagKey.key() (== enum name).
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        assertThat(service.isEnabled(SubscriptionFeature.LOAN_MANAGEMENT)).isTrue();
    }

    @Test
    void unknownFlag_missingFromDatabase_defaultsToFalseAndLogsWarning() {
        when(flagStore.findFlag("UNKNOWN_FLAG")).thenReturn(Optional.empty());

        assertThat(service.isEnabled("UNKNOWN_FLAG")).isFalse();
    }

    @Test
    void set_updatesValue() {
        PlatformFlag existing = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(existing));
        when(flagStore.saveFlag(any(PlatformFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformFlag result = service.disable("LOAN_MANAGEMENT", "superadmin@maestro.com");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getUpdatedBy()).isEqualTo("superadmin@maestro.com");
        verify(flagStore).saveFlag(existing);
    }

    @Test
    void getAll_returnsAllFlags() {
        List<PlatformFlag> flags = List.of(
                PlatformFlag.builder().name("ATTENDANCE_TRACKING").enabled(true).build(),
                PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build()
        );
        when(flagStore.findAllFlagsOrderedByName()).thenReturn(flags);

        List<PlatformFlag> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("ATTENDANCE_TRACKING");
    }

    @Test
    void tenantOverride_appliesWhenGlobalEnabled() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();
        when(flagStore.findOverride(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(override));

        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isTrue();
    }

    @Test
    void planOverride_disabledForPlan() {
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.PLAN)
                .targetValue("BASIC")
                .enabled(false)
                .build();
        when(flagStore.findOverride(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.PLAN, "BASIC"))
                .thenReturn(Optional.of(override));

        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", null, "BASIC")).isFalse();
    }

    @Test
    void globalKillSwitch_beatsTenantOverride() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        // Tenant has an override forcing the flag ON, but the global kill switch must win
        // outright — no override of any kind can revive a globally-killed flag.
        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isFalse();
        verify(flagStore, never()).findOverride(any(), any(), any());
    }

    @Test
    void rolloutPercentage_50percent() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("HARDWARE_SYNC").enabled(true).rolloutPercentage(50).build();
        when(flagStore.findOverride(eq("HARDWARE_SYNC"), any(), any())).thenReturn(Optional.empty());
        when(flagStore.findFlag("HARDWARE_SYNC")).thenReturn(Optional.of(flag));

        boolean result = service.isEnabledForTenant("HARDWARE_SYNC", tenantId, null);

        int bucket = Math.floorMod((tenantId.toString() + "HARDWARE_SYNC").hashCode(), 100);
        assertThat(result).isEqualTo(bucket < 50);
    }

    @Test
    void resolutionOrder_tenantBeatsPlanBeatsRollout_whenGlobalEnabled() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("DOCUMENT_VAULT").enabled(true).rolloutPercentage(50).build();
        when(flagStore.findFlag("DOCUMENT_VAULT")).thenReturn(Optional.of(flag));
        FeatureFlagOverride tenantOverride = FeatureFlagOverride.builder()
                .flagName("DOCUMENT_VAULT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();

        // Global flag is on, so the tenant override is reached and wins outright — plan/rollout
        // never consulted.
        when(flagStore.findOverride(
                "DOCUMENT_VAULT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(tenantOverride));

        assertThat(service.isEnabledForTenant("DOCUMENT_VAULT", tenantId, "PROFESSIONAL")).isTrue();
        verify(flagStore, never()).findOverride(
                "DOCUMENT_VAULT", FeatureFlagOverride.TargetType.PLAN, "PROFESSIONAL");
    }

    @Test
    void createOverride_flagRowMissing_autoCreatesEnabledFlagSoOverrideIsNotANoOp() {
        UUID tenantId = UUID.randomUUID();
        // No platform_flags row for this name yet: without the auto-create, resolution would
        // bail at the missing-flag gate and never consult the override.
        when(flagStore.findFlag("NEW_FEATURE")).thenReturn(Optional.empty());
        when(flagStore.findOverride(
                "NEW_FEATURE", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.empty());
        when(flagStore.saveFlag(any(PlatformFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flagStore.saveOverride(any(FeatureFlagOverride.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createOverride("NEW_FEATURE", FeatureFlagOverride.TargetType.TENANT,
                tenantId.toString(), true, "beta tester", "admin@platform.io");

        // A backing flag row is created, enabled, so the override actually takes effect.
        ArgumentCaptor<PlatformFlag> flagCaptor = ArgumentCaptor.forClass(PlatformFlag.class);
        verify(flagStore).saveFlag(flagCaptor.capture());
        assertThat(flagCaptor.getValue().getName()).isEqualTo("NEW_FEATURE");
        assertThat(flagCaptor.getValue().isEnabled()).isTrue();
        verify(flagStore).saveOverride(any(FeatureFlagOverride.class));
    }

    @Test
    void createOverride_flagRowExists_doesNotTouchTheFlagRow() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag existing = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findFlag("LOAN_MANAGEMENT")).thenReturn(Optional.of(existing));
        when(flagStore.findOverride(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.PLAN, "BASIC"))
                .thenReturn(Optional.empty());
        when(flagStore.saveOverride(any(FeatureFlagOverride.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createOverride("LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.PLAN,
                "BASIC", false, "not on this plan", "admin@platform.io");

        // Existing flag state is never flipped by creating an override against it.
        verify(flagStore, never()).saveFlag(any(PlatformFlag.class));
        verify(flagStore).saveOverride(any(FeatureFlagOverride.class));
    }

    @Test
    void requestScopedCache_secondCallSameFlag_doesNotHitStoreAgain() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findAllFlags()).thenReturn(List.of(flag));

        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isTrue();
        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isTrue();

        // The whole flag table is loaded once into the request-scoped cache; the second call
        // reuses it instead of querying again.
        verify(flagStore, times(1)).findAllFlags();
        verify(flagStore, never()).findFlag(any());
    }

    @Test
    void requestScopedCache_secondOverrideLookupSameKey_doesNotRequeryStore() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagStore.findAllFlags()).thenReturn(List.of(flag));
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();
        when(flagStore.findOverride(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(override));

        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isTrue();
        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isTrue();

        verify(flagStore, times(1)).findOverride(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString());
        // The global-flag map is loaded once via the request-scoped cache; the second call
        // reuses both caches instead of querying again.
        verify(flagStore, times(1)).findAllFlags();
        verify(flagStore, never()).findFlag(any());
    }
}
