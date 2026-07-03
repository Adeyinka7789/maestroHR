package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.platform.AdminStatsQueries;
import com.admtechhub.maestrohr.subscription.PlatformFlag;
import com.admtechhub.maestrohr.subscription.PlatformFlagService;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer test for {@link FeatureFlagsAdminController}. Same rationale as
 * {@code AdminFeatureFlagApiControllerTest}: {@code @SpringBootTest} + {@code @AutoConfigureMockMvc}
 * so the real {@code SecurityConfig} filter chain enforces the SUPER_ADMIN gate on
 * {@code /htmx/admin/**}, which a {@code @WebMvcTest} web slice would not load.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeatureFlagsAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private PlatformFlagService platformFlagService;

    @MockBean
    private AdminStatsQueries adminStatsQueries;

    @MockBean
    private SubscriptionService subscriptionService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("admin@platform.io");
        when(jwtService.extractTenantId(token)).thenReturn(TENANT_ID.toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    // ── GET renders successfully ────────────────────────────────────────────

    @Test
    void getFeatureFlagsPage_asSuperAdmin_rendersSuccessfully() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(platformFlagService.listAll()).thenReturn(List.of());
        when(adminStatsQueries.findAllTenantsWithUserCount()).thenReturn(List.of());

        mockMvc.perform(get("/htmx/admin/feature-flags")
                        .header("Authorization", "Bearer token-super")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Feature Flags")));
    }

    // ── POST enable/disable — success path ──────────────────────────────────

    @Test
    void enableFlag_asSuperAdmin_succeedsAndCallsService() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(subscriptionService.getStatus(any())).thenReturn(SubscriptionStatus.ACTIVE);
        when(platformFlagService.listAll()).thenReturn(List.of());
        when(adminStatsQueries.findAllTenantsWithUserCount()).thenReturn(List.of());
        when(platformFlagService.enable(eq("LOAN_MANAGEMENT"), anyString()))
                .thenReturn(PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build());

        mockMvc.perform(post("/htmx/admin/feature-flags/LOAN_MANAGEMENT/enable")
                        .header("Authorization", "Bearer token-super"))
                .andExpect(status().isOk());

        verify(platformFlagService).enable(eq("LOAN_MANAGEMENT"), anyString());
    }

    @Test
    void disableFlag_asSuperAdmin_succeedsAndCallsService() throws Exception {
        mockToken("token-super", "SUPER_ADMIN");
        when(subscriptionService.getStatus(any())).thenReturn(SubscriptionStatus.ACTIVE);
        when(platformFlagService.listAll()).thenReturn(List.of());
        when(adminStatsQueries.findAllTenantsWithUserCount()).thenReturn(List.of());
        when(platformFlagService.disable(eq("LOAN_MANAGEMENT"), anyString()))
                .thenReturn(PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build());

        mockMvc.perform(post("/htmx/admin/feature-flags/LOAN_MANAGEMENT/disable")
                        .header("Authorization", "Bearer token-super"))
                .andExpect(status().isOk());

        verify(platformFlagService).disable(eq("LOAN_MANAGEMENT"), anyString());
    }

    // ── SUPER_ADMIN gate ─────────────────────────────────────────────────────

    @Test
    void nonAdminRole_rejectedWith403() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(get("/htmx/admin/feature-flags")
                        .header("Authorization", "Bearer token-hr")
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }
}
