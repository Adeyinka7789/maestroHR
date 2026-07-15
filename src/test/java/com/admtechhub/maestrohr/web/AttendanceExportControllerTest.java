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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer test for {@link AttendanceExportController} — the "export all attendance data"
 * Excel download. An HR admin gets a spreadsheet response (xlsx content type + an attachment
 * filename derived from the range); an EMPLOYEE is refused. This is a plain download navigation
 * (no HX-Request), so the denial is a normal 403 rather than an in-place fragment.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceExportControllerTest {

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
    void export_asHrAdmin_returnsXlsxAttachment() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(get("/htmx/attendance/export")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .header("Authorization", "Bearer token-hr"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        containsString("spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition",
                        containsString("attendance-2026-06-01-to-2026-06-30.xlsx")));
    }

    @Test
    void export_asEmployee_isForbidden() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        mockMvc.perform(get("/htmx/attendance/export")
                        .header("Authorization", "Bearer token-employee"))
                .andExpect(status().isForbidden());
    }
}
