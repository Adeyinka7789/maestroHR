package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.leave.LeaveService;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.FeatureDisabledException;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-gate test for the HTMX leave approve/reject writes. These are HR/manager actions that
 * mutate leave balances, so they carry the same {@code @PreAuthorize("hasAnyRole('HR_ADMIN',
 * 'DEPT_MANAGER')")} as their REST counterparts on {@link com.admtechhub.maestrohr.leave.LeaveController}.
 * {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} so the real SecurityConfig filter chain and
 * {@code @EnableMethodSecurity} enforce the gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeaveListControllerAuthzTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtService jwtService;
    @MockBean private LeaveService leaveService;
    @MockBean private LeaveListService leaveListService;
    @MockBean private UserRepository userRepository;
    @MockBean private EmployeeService employeeService;
    @MockBean private FeatureAccessService featureAccessService; // let @RequiresFeature pass

    private static final UUID REQUEST_ID = UUID.randomUUID();

    private void mockToken(String token, String email, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);
        when(jwtService.extractTenantId(token)).thenReturn(UUID.randomUUID().toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    // ── denied: a self-service EMPLOYEE must not approve/reject ──────────────────

    @Test
    void employee_cannotApprove_403() throws Exception {
        mockToken("tok-emp", "emp@x.io", "EMPLOYEE");

        mockMvc.perform(post("/htmx/leave/{id}/approve", REQUEST_ID)
                        .header("Authorization", "Bearer tok-emp"))
                .andExpect(status().isForbidden());

        verify(leaveService, never()).approveLeaveRequest(any(), any(), any());
    }

    @Test
    void employee_cannotReject_403() throws Exception {
        mockToken("tok-emp", "emp@x.io", "EMPLOYEE");

        mockMvc.perform(post("/htmx/leave/{id}/reject", REQUEST_ID)
                        .param("reason", "no")
                        .header("Authorization", "Bearer tok-emp"))
                .andExpect(status().isForbidden());

        verify(leaveService, never()).rejectLeaveRequest(any(), any());
    }

    // ── allowed: a manager passes the gate through to the service ────────────────

    @Test
    void deptManager_canApprove_reachesService() throws Exception {
        mockToken("tok-mgr", "mgr@x.io", "DEPT_MANAGER");
        User manager = mock(User.class);
        when(manager.getId()).thenReturn(UUID.randomUUID());
        when(userRepository.findByEmail("mgr@x.io")).thenReturn(Optional.of(manager));
        // Minimal view so the "leave :: table" fragment renders after the write.
        when(leaveListService.buildList(any(), any())).thenReturn(new LeaveListView(
                List.of(), 0, null, null, List.of(), List.of(), List.of(), null, false, true));

        mockMvc.perform(post("/htmx/leave/{id}/approve", REQUEST_ID)
                        .header("Authorization", "Bearer tok-mgr"))
                .andExpect(status().isOk());

        // Passing the gate is the assertion: the handler ran and delegated to the service.
        verify(leaveService).approveLeaveRequest(eq(REQUEST_ID), any(), isNull());
    }

    // ── the read page itself is feature-gated (disabling the feature blocks access) ──

    @Test
    void leaveTableRead_isFeatureGated() throws Exception {
        mockToken("tok-hr", "hr@x.io", "HR_ADMIN");
        when(leaveListService.buildList(any(), any())).thenReturn(new LeaveListView(
                List.of(), 0, null, null, List.of(), List.of(), List.of(), null, false, true));

        mockMvc.perform(get("/htmx/leave/table")
                        .header("Authorization", "Bearer tok-hr")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk());

        // The read now consults the feature gate, so a disabled LEAVE_MANAGEMENT blocks the page.
        verify(featureAccessService).require(SubscriptionFeature.LEAVE_MANAGEMENT);
    }

    @Test
    void leaveRead_whenFeatureDisabled_showsLockedStateWithNoData() throws Exception {
        mockToken("tok-hr", "hr@x.io", "HR_ADMIN");
        doThrow(new FeatureDisabledException(SubscriptionFeature.LEAVE_MANAGEMENT))
                .when(featureAccessService).require(SubscriptionFeature.LEAVE_MANAGEMENT);

        mockMvc.perform(get("/htmx/leave/table")
                        .header("Authorization", "Bearer tok-hr")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave management is not available right now")))
                // The locked fragment must not carry the request table / Apply action.
                .andExpect(content().string(not(containsString("Apply for Leave"))));

        // No data was loaded: neither list nor content builder ran.
        verify(leaveListService, never()).buildList(any(), any());
        verify(leaveListService, never()).buildContent(any(), any());
    }
}
