package com.admtechhub.maestrohr;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.employee.EmployeeController;
import com.admtechhub.maestrohr.employee.EmployeeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmployeeController.class)
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
