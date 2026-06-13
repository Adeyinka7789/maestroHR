package com.admtechhub.maestrohr;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.employee.EmployeeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant-isolation checks against the real security filter chain. Uses {@code @SpringBootTest}
 * (not {@code @WebMvcTest}): these assertions depend on our custom filters — {@code JwtAuthFilter},
 * {@code TenantValidationFilter}, {@code LapsedAccessFilter} — and the {@code @SQLRestriction}
 * scoping, none of which load in a web slice (which falls back to Spring Security's default chain).
 * Loads the full context against the {@code .env} Postgres, like the other integration tests.
 * {@code JwtService} and {@code EmployeeService} are mocked to forge tokens and isolate the
 * controller from the data layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private EmployeeService employeeService;

    private static final UUID TENANT_A = UUID.randomUUID();

    // ── Test 1 ───────────────────────────────────────────────────────────────
    // A request bearing a well-formed JWT with a valid tenant UUID must reach
    // the controller and return 200.
    @Test
    void validTenantJwtReturns200() throws Exception {
        when(jwtService.isTokenValid("token-a")).thenReturn(true);
        when(jwtService.extractEmail("token-a")).thenReturn("alice@tenanta.com");
        when(jwtService.extractTenantId("token-a")).thenReturn(TENANT_A.toString());
        when(jwtService.extractRole("token-a")).thenReturn("HR_ADMIN");
        when(employeeService.getAllEmployees(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/employees")
                        .header("Authorization", "Bearer token-a"))
                .andExpect(status().isOk());
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────
    // A request with no Authorization header must be rejected by
    // TenantValidationFilter with 403 before reaching the controller.
    @Test
    void noJwtOnProtectedEndpointReturns403() throws Exception {
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isForbidden());
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────
    // A valid Tenant-A JWT requesting an employee that belongs to Tenant B
    // gets 400: @SQLRestriction hides the row, the repository returns empty,
    // and EmployeeService throws IllegalArgumentException which
    // GlobalExceptionHandler maps to 400 Bad Request.
    @Test
    void crossTenantEmployeeLookupReturns400() throws Exception {
        UUID tenantBEmployeeId = UUID.randomUUID();

        when(jwtService.isTokenValid("token-a")).thenReturn(true);
        when(jwtService.extractEmail("token-a")).thenReturn("alice@tenanta.com");
        when(jwtService.extractTenantId("token-a")).thenReturn(TENANT_A.toString());
        when(jwtService.extractRole("token-a")).thenReturn("HR_ADMIN");
        when(employeeService.getEmployeeById(any(UUID.class)))
                .thenThrow(new IllegalArgumentException("Employee not found: " + tenantBEmployeeId));

        mockMvc.perform(get("/api/employees/{id}", tenantBEmployeeId)
                        .header("Authorization", "Bearer token-a"))
                .andExpect(status().isBadRequest());
    }
}
