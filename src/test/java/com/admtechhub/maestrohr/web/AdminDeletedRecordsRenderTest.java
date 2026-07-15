package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.AuthRequest;
import com.admtechhub.maestrohr.auth.AuthResponse;
import com.admtechhub.maestrohr.auth.AuthService;
import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.platform.DeletedRecordsQueries;
import com.admtechhub.maestrohr.platform.DeletedRecordsQueries.DeletedType;
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
 * End-to-end check that a self-deleted company surfaces on the super-admin trash page with the new
 * TENANT ("COMPANY") row type. Seeds a real company, soft-deletes it via {@link AccountService},
 * then renders {@code /htmx/admin/deleted-records} as SUPER_ADMIN (a real web context, so the
 * {@code @{...}} restore/purge links resolve) and asserts the company appears with the COMPANY
 * badge. Cleans up by purging the tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminDeletedRecordsRenderTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private AccountService accountService;
    @Autowired private DeletedRecordsQueries deletedRecordsQueries;

    @MockBean private JwtService jwtService;

    @Test
    void deletedCompany_showsOnSuperAdminTrashPageAsCompanyRow() throws Exception {
        // Seed a company and soft-delete it through the self-service flow.
        String email = "trash-owner-" + UUID.randomUUID() + "@example.test";
        String company = "TrashCo " + UUID.randomUUID().toString().substring(0, 8);
        AuthRequest.Register reg = new AuthRequest.Register();
        reg.setCompanyName(company);
        reg.setIndustry("Technology");
        reg.setCompanySize("1-10");
        reg.setAdminEmail(email);
        reg.setPassword("password123");
        AuthResponse resp = authService.register(reg);
        UUID tenantId = resp.getTenantId();
        accountService.deleteCompany(email, tenantId, company, "password123");

        try {
            when(jwtService.isTokenValid("super")).thenReturn(true);
            when(jwtService.extractEmail("super")).thenReturn("root@platform.io");
            when(jwtService.extractTenantId("super")).thenReturn(UUID.randomUUID().toString());
            when(jwtService.extractRole("super")).thenReturn("SUPER_ADMIN");

            mockMvc.perform(get("/htmx/admin/deleted-records")
                            .header("Authorization", "Bearer super")
                            .header("HX-Request", "true"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(">COMPANY<")))
                    .andExpect(content().string(containsString(company)));
        } finally {
            deletedRecordsQueries.purgeNow(DeletedType.TENANT, tenantId);
        }
    }
}
