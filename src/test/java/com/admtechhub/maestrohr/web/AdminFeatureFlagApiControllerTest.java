package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.subscription.FeatureFlagOverride;
import com.admtechhub.maestrohr.subscription.PlatformFlag;
import com.admtechhub.maestrohr.subscription.PlatformFlagService;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer test for {@link AdminFeatureFlagApiController}. Uses {@code @SpringBootTest} +
 * {@code @AutoConfigureMockMvc} (not {@code @WebMvcTest}) — mirrors {@code TenantIsolationTest}:
 * the SUPER_ADMIN gate is enforced by the real {@code SecurityConfig} filter chain
 * ({@code JwtAuthFilter}, {@code TenantValidationFilter}) plus {@code @PreAuthorize}, none of
 * which load under a {@code @WebMvcTest} web slice. {@link JwtService} is mocked to forge
 * tokens; {@link PlatformFlagService} and {@link SubscriptionService} are mocked to isolate
 * from the database (the latter is consulted by {@code LapsedAccessFilter} on every non-GET
 * request once a tenant is bound).
 *
 * <p>{@link AdminStatsQueries} is mocked here too even though this controller never calls it —
 * Spring's test context cache keys on the exact set of {@code @MockBean}s, and each distinct
 * key gets its own Hikari pool that stays open for the rest of the suite. Matching
 * {@code FeatureFlagsAdminControllerTest}'s mock set exactly lets both classes share one cached
 * context/pool instead of two (see {@code PrivilegedDataSourceConfig}'s Javadoc on this
 * per-context connection cost).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminFeatureFlagApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private PlatformFlagService platformFlagService;

    @MockBean
    private SubscriptionService subscriptionService;

    @MockBean
    private AdminStatsQueries adminStatsQueries;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("admin@platform.io");
        when(jwtService.extractTenantId(token)).thenReturn(TENANT_ID.toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    // ── GET overrides ────────────────────────────────────────────────────────

    @Test
    void getOverrides_asSuperAdmin_returns200WithList() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        FeatureFlagOverride override = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(TENANT_ID.toString())
                .enabled(true)
                .build();
        when(platformFlagService.listOverrides("LOAN_MANAGEMENT")).thenReturn(List.of(override));

        mockMvc.perform(get("/api/admin/feature-flags/LOAN_MANAGEMENT/overrides")
                        .header("Authorization", "Bearer token-super"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].targetValue").value(TENANT_ID.toString()));
    }

    // ── POST overrides — success ────────────────────────────────────────────

    @Test
    void createOverride_valid_returns200AndCallsService() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(subscriptionService.getStatus(any())).thenReturn(SubscriptionStatus.ACTIVE);
        FeatureFlagOverride saved = FeatureFlagOverride.builder()
                .flagName("LOAN_MANAGEMENT")
                .targetType(FeatureFlagOverride.TargetType.TENANT)
                .targetValue(TENANT_ID.toString())
                .enabled(true)
                .reason("early access")
                .build();
        when(platformFlagService.createOverride(
                eq("LOAN_MANAGEMENT"), eq(FeatureFlagOverride.TargetType.TENANT),
                eq(TENANT_ID.toString()), eq(true), eq("early access"), anyString()))
                .thenReturn(saved);

        String body = objectMapper.writeValueAsString(Map.of(
                "targetType", "TENANT",
                "targetValue", TENANT_ID.toString(),
                "enabled", true,
                "reason", "early access"));

        mockMvc.perform(post("/api/admin/feature-flags/LOAN_MANAGEMENT/overrides")
                        .header("Authorization", "Bearer token-super")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetValue").value(TENANT_ID.toString()));

        verify(platformFlagService).createOverride(
                eq("LOAN_MANAGEMENT"), eq(FeatureFlagOverride.TargetType.TENANT),
                eq(TENANT_ID.toString()), eq(true), eq("early access"), anyString());
    }

    // ── POST overrides — validation failure ─────────────────────────────────

    @Test
    void createOverride_missingTargetValue_returns400() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(subscriptionService.getStatus(any())).thenReturn(SubscriptionStatus.ACTIVE);

        String body = objectMapper.writeValueAsString(Map.of(
                "targetType", "TENANT",
                "enabled", true));

        mockMvc.perform(post("/api/admin/feature-flags/LOAN_MANAGEMENT/overrides")
                        .header("Authorization", "Bearer token-super")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── DELETE override ──────────────────────────────────────────────────────

    @Test
    void deleteOverride_asSuperAdmin_returns200AndCallsService() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(subscriptionService.getStatus(any())).thenReturn(SubscriptionStatus.ACTIVE);
        UUID overrideId = UUID.randomUUID();

        mockMvc.perform(delete("/api/admin/feature-flags/LOAN_MANAGEMENT/overrides/{id}", overrideId)
                        .header("Authorization", "Bearer token-super"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(platformFlagService).deleteOverride(overrideId);
    }

    // ── PATCH rollout ────────────────────────────────────────────────────────

    @Test
    void setRollout_asSuperAdmin_returns200WithUpdatedFlag() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(subscriptionService.getStatus(any())).thenReturn(SubscriptionStatus.ACTIVE);
        PlatformFlag updated = PlatformFlag.builder()
                .name("LOAN_MANAGEMENT").enabled(true).rolloutPercentage(50).build();
        when(platformFlagService.setRolloutPercentage(eq("LOAN_MANAGEMENT"), eq(50), anyString()))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/admin/feature-flags/LOAN_MANAGEMENT/rollout")
                        .header("Authorization", "Bearer token-super")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"percentage\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rolloutPercentage").value(50));
    }

    // ── SUPER_ADMIN gate ─────────────────────────────────────────────────────

    @Test
    void nonAdminRole_rejectedWith403() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(get("/api/admin/feature-flags/LOAN_MANAGEMENT/overrides")
                        .header("Authorization", "Bearer token-hr"))
                .andExpect(status().isForbidden());
    }
}
