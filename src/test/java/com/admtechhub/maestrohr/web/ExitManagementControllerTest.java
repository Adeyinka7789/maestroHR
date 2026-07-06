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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reproduces the "Invalid UUID string: 07" bug from POST /htmx/exit-management/create: before
 * the fix, the employeeId field was a free-text input, so a non-UUID value reached
 * {@code @RequestParam UUID employeeId} and threw an unhandled
 * MethodArgumentTypeMismatchException (a raw 500, and an unhandled/Fatal Sentry event). The form
 * is now a dropdown bound to real employee ids (see exit-management.html), but the controller
 * must also degrade gracefully for any value that still isn't a UUID (e.g. a raw API caller).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExitManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("hr@tenant.io");
        when(jwtService.extractTenantId(token)).thenReturn(TENANT_ID.toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    @Test
    void createExit_nonUuidEmployeeId_returns200WithCleanError_notRaw500() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(post("/htmx/exit-management/create")
                        .header("Authorization", "Bearer token-hr")
                        .header("HX-Request", "true")
                        .param("employeeId", "07")
                        .param("exitType", "RESIGNATION")
                        .param("lastWorkingDay", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invalid value for &#39;employeeId&#39;")));
    }
}
