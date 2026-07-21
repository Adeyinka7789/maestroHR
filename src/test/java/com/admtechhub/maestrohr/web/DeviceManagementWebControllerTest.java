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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer test for {@link DeviceManagementWebController}, confirming the fix for the
 * "class-level role gate was commented out" gap: an EMPLOYEE hitting any GET or POST route in
 * this controller must get a clean 200 with an in-place access-denied fragment, NOT a raw 403
 * (which would trip layout.js's {@code htmx:responseError} logout redirect), and an authorized
 * role must still reach the real page/service.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceManagementWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private com.admtechhub.maestrohr.subscription.FeatureAccessService featureAccessService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("user@tenant.io");
        when(jwtService.extractTenantId(token)).thenReturn(TENANT_ID.toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    @Test
    void devicesPage_asEmployee_returns200WithAccessDeniedMessage_notRaw403() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        mockMvc.perform(get("/htmx/attendance/devices")
                        .header("Authorization", "Bearer token-employee")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("have access to this page")));
    }

    @Test
    void revokeDevice_asEmployee_returns200WithAccessDeniedMessage_notRaw403() throws Exception {
        mockToken("token-employee", "EMPLOYEE");

        // A garbage keyId is fine — @ModelAttribute checkAccess() denies before the handler
        // body (and therefore the service call) ever runs.
        mockMvc.perform(post("/htmx/attendance/devices/" + UUID.randomUUID() + "/revoke")
                        .header("Authorization", "Bearer token-employee")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("have access to this page")));
    }

    @Test
    void devicesPage_asHrAdmin_returns200WithDeviceSyncContent() throws Exception {
        mockToken("token-hr", "HR_ADMIN");

        mockMvc.perform(get("/htmx/attendance/devices")
                        .header("Authorization", "Bearer token-hr")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Device Sync")));
    }
}
