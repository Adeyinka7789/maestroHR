package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer test for {@link AttendanceAnalyticsController} — the whole-team analytics tab.
 * Mirrors {@link AttendanceCalendarControllerTest}: this route is admin/manager-only with no
 * EMPLOYEE self-access path, so EMPLOYEE is denied (rendered as an in-place fragment, HTTP 200,
 * not a raw 403 that would trip layout.js's logout redirect), and an admin role succeeds.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceAnalyticsControllerTest {

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
    void analytics_asEmployee_returns200WithAccessDeniedMessage_notRaw403() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        mockMvc.perform(get("/htmx/attendance/analytics")
                        .header("Authorization", "Bearer token-employee")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("have access to this page")));
    }

    @Test
    void analytics_asHrAdmin_returns200WithAnalyticsContent() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(get("/htmx/attendance/analytics")
                        .header("Authorization", "Bearer token-hr")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Team attendance analytics")))
                .andExpect(content().string(containsString("Export to Excel")));
    }

    @Test
    void analytics_nonHtmxVisit_returnsAppShell() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(get("/htmx/attendance/analytics")
                        .header("Authorization", "Bearer token-hr"))
                .andExpect(status().isOk());
    }
}
