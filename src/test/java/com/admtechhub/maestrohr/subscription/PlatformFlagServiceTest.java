package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

@ExtendWith(MockitoExtension.class)
class PlatformFlagServiceTest {

    @Mock private PlatformFlagRepository flagRepository;
    @Mock private FeatureFlagOverrideRepository overrideRepository;
    @Mock private AuditTrailService auditTrailService;

    @InjectMocks private PlatformFlagService service;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void isEnabled_flagExists_returnsValue() {
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isFalse();
    }

    @Test
    void unknownFlag_missingFromDatabase_defaultsToFalseAndLogsWarning() {
        when(flagRepository.findByName("UNKNOWN_FLAG")).thenReturn(Optional.empty());

        assertThat(service.isEnabled("UNKNOWN_FLAG")).isFalse();
    }

    @Test
    void set_updatesValue() {
        PlatformFlag existing = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(existing));
        when(flagRepository.save(any(PlatformFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformFlag result = service.disable("LOAN_MANAGEMENT", "superadmin@maestro.com");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getUpdatedBy()).isEqualTo("superadmin@maestro.com");
        verify(flagRepository).save(existing);
    }

    @Test
    void getAll_returnsAllFlags() {
        List<PlatformFlag> flags = List.of(
                PlatformFlag.builder().name("ATTENDANCE_TRACKING").enabled(true).build(),
                PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build()
        );
        when(flagRepository.findAllByOrderByNameAsc()).thenReturn(flags);

        List<PlatformFlag> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("ATTENDANCE_TRACKING");
    }

    @Test
    void tenantOverride_appliesWhenGlobalEnabled() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(override));

        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isTrue();
    }

    @Test
    void planOverride_disabledForPlan() {
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.PLAN)
                .targetValue("BASIC")
                .enabled(false)
                .build();
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.PLAN, "BASIC"))
                .thenReturn(Optional.of(override));

        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", null, "BASIC")).isFalse();
    }

    @Test
    void globalKillSwitch_beatsTenantOverride() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        // Tenant has an override forcing the flag ON, but the global kill switch must win
        // outright — no override of any kind can revive a globally-killed flag.
        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isFalse();
        verify(overrideRepository, never()).findByFlagNameAndTargetTypeAndTargetValue(any(), any(), any());
    }

    @Test
    void rolloutPercentage_50percent() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("HARDWARE_SYNC").enabled(true).rolloutPercentage(50).build();
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(eq("HARDWARE_SYNC"), any(), any()))
                .thenReturn(Optional.empty());
        when(flagRepository.findByName("HARDWARE_SYNC")).thenReturn(Optional.of(flag));

        boolean result = service.isEnabledForTenant("HARDWARE_SYNC", tenantId, null);

        int bucket = Math.floorMod((tenantId.toString() + "HARDWARE_SYNC").hashCode(), 100);
        assertThat(result).isEqualTo(bucket < 50);
    }

    @Test
    void resolutionOrder_tenantBeatsPlanBeatsRollout_whenGlobalEnabled() {
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("DOCUMENT_VAULT").enabled(true).rolloutPercentage(50).build();
        when(flagRepository.findByName("DOCUMENT_VAULT")).thenReturn(Optional.of(flag));
        FeatureFlagOverride tenantOverride = FeatureFlagOverride.builder()
                .flagName("DOCUMENT_VAULT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();

        // Global flag is on, so the tenant override is reached and wins outright — plan/rollout
        // never consulted.
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(
                "DOCUMENT_VAULT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(tenantOverride));

        assertThat(service.isEnabledForTenant("DOCUMENT_VAULT", tenantId, "PROFESSIONAL")).isTrue();
        verify(overrideRepository, never()).findByFlagNameAndTargetTypeAndTargetValue(
                "DOCUMENT_VAULT", FeatureFlagOverride.TargetType.PLAN, "PROFESSIONAL");
    }

    @Test
    void requestScopedCache_secondCallSameFlag_doesNotHitFlagRepositoryAgain() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findAll()).thenReturn(List.of(flag));

        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isTrue();
        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isTrue();

        // The whole flag table is loaded once into the request-scoped cache; the second call
        // reuses it instead of querying again.
        verify(flagRepository, times(1)).findAll();
        verify(flagRepository, never()).findByName(any());
    }

    @Test
    void requestScopedCache_secondOverrideLookupSameKey_doesNotRequeryOverrideRepository() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        UUID tenantId = UUID.randomUUID();
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findAll()).thenReturn(List.of(flag));
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(override));

        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isTrue();
        assertThat(service.isEnabledForTenant("LOAN_MANAGEMENT", tenantId, null)).isTrue();

        verify(overrideRepository, times(1)).findByFlagNameAndTargetTypeAndTargetValue(
                "LOAN_MANAGEMENT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString());
        // The global-flag map is loaded once via the request-scoped cache; the second call
        // reuses both caches instead of querying again.
        verify(flagRepository, times(1)).findAll();
        verify(flagRepository, never()).findByName(any());
    }
}
