package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer test for {@link PageController#settings}, confirming the fix for the "no role
 * check on GET /htmx/settings" gap: an EMPLOYEE hitting the HTMX fragment fetch must get a
 * clean 200 with an in-place access-denied fragment, NOT a raw 403 — a 403 there would trip
 * layout.js's {@code htmx:responseError} handler and log the user out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("user@tenant.io");
        when(jwtService.extractTenantId(token)).thenReturn(TENANT_ID.toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    @Test
    void settingsFragment_asEmployee_returns200WithAccessDeniedMessage_notRaw403() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        // Thymeleaf HTML-escapes the apostrophe (don't -> don&#39;t), so match around it.
        mockMvc.perform(get("/htmx/settings")
                        .header("Authorization", "Bearer token-employee")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("have access to this page")));
    }

    @Test
    void settingsFragment_asHrAdmin_reachesSettingsForward_notAccessDenied() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        // MockMvc doesn't serve static-resource bytes through an internal forward, so assert on
        // the forward target itself rather than rendered body: an authorized role must reach the
        // un-gated "forward:/settings.html" branch, not the access-denied exception handler.
        MvcResult result = mockMvc.perform(get("/htmx/settings")
                        .header("Authorization", "Bearer token-hr")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getForwardedUrl()).isEqualTo("/settings.html");
    }

    @Test
    void settingsColdVisit_asEmployee_returnsAppShellRegardlessOfRole() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        mockMvc.perform(get("/htmx/settings")
                        .header("Authorization", "Bearer token-employee"))
                .andExpect(status().isOk());
    }
}
