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
 * HTTP-layer test for {@link AttendanceListController}, confirming the fix for the
 * "company-wide roster readable by any authenticated role" gap: the read routes now use the
 * same role list as the existing {@code /htmx/attendance/mark} write's {@code @PreAuthorize}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceListControllerTest {

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
    void attendanceFragment_asEmployee_returns200WithAccessDeniedMessage_notRaw403() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        mockMvc.perform(get("/htmx/attendance")
                        .header("Authorization", "Bearer token-employee")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("have access to this page")));
    }

    @Test
    void attendanceTable_asEmployee_returns200WithAccessDeniedMessage_notRaw403() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        mockMvc.perform(get("/htmx/attendance/table")
                        .header("Authorization", "Bearer token-employee")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("have access to this page")));
    }

    @Test
    void attendanceFragment_asDeptManager_returns200WithRosterContent() throws Exception {
        // DEPT_MANAGER is in /mark's existing @PreAuthorize list — the read gate must mirror it.
        mockToken("token-manager", "DEPT_MANAGER");

        mockMvc.perform(get("/htmx/attendance")
                        .header("Authorization", "Bearer token-manager")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Daily attendance roster")));
    }

    @Test
    void attendanceTable_asDeptManager_returns200WithTableContent() throws Exception {
        mockToken("token-manager", "DEPT_MANAGER");

        mockMvc.perform(get("/htmx/attendance/table")
                        .header("Authorization", "Bearer token-manager")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("attendance-active-status")));
    }
}
