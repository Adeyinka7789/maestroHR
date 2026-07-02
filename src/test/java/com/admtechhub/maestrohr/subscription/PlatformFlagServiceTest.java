package com.admtechhub.maestrohr.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformFlagServiceTest {

    @Mock private PlatformFlagRepository flagRepository;
    @Mock private FeatureFlagOverrideRepository overrideRepository;

    @InjectMocks private PlatformFlagService service;

    @Test
    void isEnabled_flagExists_returnsValue() {
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isFalse();
    }

    @Test
    void isEnabled_flagMissing_defaultsToTrue() {
        when(flagRepository.findByName("UNKNOWN_FLAG")).thenReturn(Optional.empty());

        assertThat(service.isEnabled("UNKNOWN_FLAG")).isTrue();
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
    void tenantOverride_enabledWhenGlobalDisabled() {
        UUID tenantId = UUID.randomUUID();
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
        // Global flag is never even consulted once a tenant override is found.
        verify(flagRepository, never()).findByName(any());
    }

    @Test
    void planOverride_disabledForPlan() {
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
    void resolutionOrder_tenantBeatsPlanBeatsRolloutBeatsGlobal() {
        UUID tenantId = UUID.randomUUID();
        FeatureFlagOverride tenantOverride = FeatureFlagOverride.builder()
                .flagName("DOCUMENT_VAULT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(tenantId.toString())
                .enabled(true)
                .build();

        // Tenant override present → wins outright, plan/global/rollout never consulted.
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(
                "DOCUMENT_VAULT", FeatureFlagOverride.TargetType.TENANT, tenantId.toString()))
                .thenReturn(Optional.of(tenantOverride));

        assertThat(service.isEnabledForTenant("DOCUMENT_VAULT", tenantId, "PROFESSIONAL")).isTrue();
        verify(overrideRepository, never()).findByFlagNameAndTargetTypeAndTargetValue(
                "DOCUMENT_VAULT", FeatureFlagOverride.TargetType.PLAN, "PROFESSIONAL");
        verify(flagRepository, never()).findByName(any());
    }
}
