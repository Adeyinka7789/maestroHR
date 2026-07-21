package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.flags.FlagContextResolver;
import com.admtechhub.maestrohr.flags.PlatformFlagService;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveFeaturesControllerTest {

    @Mock private PlatformFlagService platformFlagService;
    @Mock private FlagContextResolver contextResolver;

    @InjectMocks private ActiveFeaturesController controller;

    @Test
    void activeFeatures_resolvesPerTenant_soATenantDisabledFeatureIsExcluded() {
        UUID tenantId = UUID.randomUUID();
        when(contextResolver.currentContext())
                .thenReturn(new FlagContextResolver.FlagContext(tenantId, "PROFESSIONAL"));

        // Everything on for this tenant except LEAVE_MANAGEMENT (disabled via a per-tenant override).
        lenient().when(platformFlagService.isEnabledForTenant(
                org.mockito.ArgumentMatchers.anyString(), eq(tenantId), eq("PROFESSIONAL"))).thenReturn(true);
        when(platformFlagService.isEnabledForTenant(
                eq(SubscriptionFeature.LEAVE_MANAGEMENT.name()), eq(tenantId), eq("PROFESSIONAL"))).thenReturn(false);

        ApiResponse<List<String>> body = controller.activeFeatures().getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData())
                .contains(SubscriptionFeature.LOAN_MANAGEMENT.name())
                .doesNotContain(SubscriptionFeature.LEAVE_MANAGEMENT.name());
    }
}
