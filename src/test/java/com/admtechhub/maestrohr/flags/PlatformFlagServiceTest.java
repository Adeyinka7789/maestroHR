package com.admtechhub.maestrohr.flags;

import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.flags.FeatureFlagOverride.TargetType;
import com.admtechhub.maestrohr.subscription.FeatureFlagOverrideRepository;
import com.admtechhub.maestrohr.subscription.PlatformFlagRepository;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import io.github.adeyinka7789.wunmi.FlagEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Since the wunmi migration, {@link PlatformFlagService} <b>delegates resolution</b> to the wunmi
 * {@link FlagEngine} and does <b>management</b> directly on the repositories + audit trail. This
 * verifies both halves (the layered resolution algorithm itself is covered by wunmi's own tests).
 */
@ExtendWith(MockitoExtension.class)
class PlatformFlagServiceTest {

    @Mock private FlagEngine flagEngine;
    @Mock private PlatformFlagRepository flagRepository;
    @Mock private FeatureFlagOverrideRepository overrideRepository;
    @Mock private AuditTrailService auditTrailService;

    @InjectMocks private PlatformFlagService service;

    // ── Resolution delegates to the engine ──────────────────────────────────────

    @Test
    void isEnabled_byName_delegatesToEngine() {
        when(flagEngine.isEnabled("LOAN_MANAGEMENT")).thenReturn(true);
        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isTrue();
    }

    @Test
    void isEnabled_typedKey_delegatesByKeyName() {
        when(flagEngine.isEnabled("LOAN_MANAGEMENT")).thenReturn(true);
        assertThat(service.isEnabled(SubscriptionFeature.LOAN_MANAGEMENT)).isTrue();
    }

    @Test
    void isEnabledForTenant_passesTenantAsSubjectStringAndPlanAsSegment() {
        UUID tenantId = UUID.randomUUID();
        when(flagEngine.resolve("LEAVE_MANAGEMENT", tenantId.toString(), "PROFESSIONAL")).thenReturn(true);

        assertThat(service.isEnabledForTenant("LEAVE_MANAGEMENT", tenantId, "PROFESSIONAL")).isTrue();
        verify(flagEngine).resolve("LEAVE_MANAGEMENT", tenantId.toString(), "PROFESSIONAL");
    }

    @Test
    void isEnabledForTenant_nullTenant_passesNullSubject() {
        when(flagEngine.resolve("LEAVE_MANAGEMENT", null, null)).thenReturn(false);
        assertThat(service.isEnabledForTenant("LEAVE_MANAGEMENT", null, null)).isFalse();
        verify(flagEngine).resolve("LEAVE_MANAGEMENT", null, null);
    }

    // ── Management writes rows + audits ──────────────────────────────────────────

    @Test
    void disable_updatesRowAndAudits() {
        PlatformFlag existing = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(existing));
        when(flagRepository.save(any(PlatformFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformFlag result = service.disable("LOAN_MANAGEMENT", "admin@x.io");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getUpdatedBy()).isEqualTo("admin@x.io");
        verify(auditTrailService).record(isNull(), eq("admin@x.io"), eq("FEATURE_FLAG_CHANGED"),
                eq("PLATFORM_FLAG"), eq("LOAN_MANAGEMENT"), anyString(), anyString(), isNull(),
                anyInt(), anyString());
    }

    @Test
    void listAll_readsOrderedFromRepository() {
        List<PlatformFlag> flags = List.of(PlatformFlag.builder().name("A").enabled(true).build());
        when(flagRepository.findAllByOrderByNameAsc()).thenReturn(flags);
        assertThat(service.listAll()).isEqualTo(flags);
    }

    @Test
    void setRolloutPercentage_outOfRange_rejected() {
        assertThatThrownBy(() -> service.setRolloutPercentage("A", 150, "admin@x.io"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(flagRepository, never()).save(any());
    }

    @Test
    void createOverride_flagRowMissing_autoCreatesFlagThenSavesOverride() {
        UUID tenantId = UUID.randomUUID();
        when(flagRepository.findByName("NEW")).thenReturn(Optional.empty());
        when(overrideRepository.findByFlagNameAndTargetTypeAndTargetValue(
                "NEW", TargetType.TENANT, tenantId.toString())).thenReturn(Optional.empty());
        when(flagRepository.save(any(PlatformFlag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(overrideRepository.save(any(FeatureFlagOverride.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createOverride("NEW", TargetType.TENANT, tenantId.toString(), true, "beta", "admin@x.io");

        verify(flagRepository).save(any(PlatformFlag.class));      // backing flag auto-created
        verify(overrideRepository).save(any(FeatureFlagOverride.class));
    }

    @Test
    void deleteOverride_removesRowAndAudits() {
        UUID id = UUID.randomUUID();
        FeatureFlagOverride existing = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT").targetType(TargetType.TENANT).targetValue("t").enabled(true).build();
        when(overrideRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deleteOverride(id);

        verify(overrideRepository).deleteById(id);
        verify(auditTrailService).record(isNull(), any(), eq("FEATURE_FLAG_CHANGED"), eq("PLATFORM_FLAG"),
                eq("LOAN_MANAGEMENT"), anyString(), anyString(), isNull(), anyInt(), anyString());
    }
}
