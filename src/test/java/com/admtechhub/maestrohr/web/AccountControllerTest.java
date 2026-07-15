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
 * HTTP-layer test for {@link AccountController}. Confirms the self-service companies fragment is
 * reachable by any authenticated user, and that acting on a company the caller does NOT belong to
 * is refused in place (HTTP 200 + banner, no swap-breaking error) rather than mutating anything —
 * the anti-IDOR guard in {@link AccountService}. The mock token's email has no seeded membership,
 * so every company action resolves to "not a member".
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtService jwtService;

    private static final UUID TENANT_ID = UUID.randomUUID();

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("nobody-" + UUID.randomUUID() + "@example.test");
        when(jwtService.extractTenantId(token)).thenReturn(TENANT_ID.toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    @Test
    void companies_isReachableByAnyAuthenticatedUser() throws Exception {
        mockToken("token-user", "HR_ADMIN");

        mockMvc.perform(get("/htmx/account/companies")
                        .header("Authorization", "Bearer token-user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your Companies")));
    }

    @Test
    void delete_onCompanyCallerDoesNotBelongTo_isRefusedInPlace() throws Exception {
        mockToken("token-user", "HR_ADMIN");

        mockMvc.perform(post("/htmx/account/company/" + UUID.randomUUID() + "/delete")
                        .param("companyName", "Whatever Ltd")
                        .param("password", "whatever")
                        .header("Authorization", "Bearer token-user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not a member")));
    }

    @Test
    void leave_onCompanyCallerDoesNotBelongTo_isRefusedInPlace() throws Exception {
        mockToken("token-user", "HR_ADMIN");

        mockMvc.perform(post("/htmx/account/company/" + UUID.randomUUID() + "/leave")
                        .header("Authorization", "Bearer token-user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not a member")));
    }
}
