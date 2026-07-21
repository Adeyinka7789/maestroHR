package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.attendance.AttendanceService;
import com.admtechhub.maestrohr.attendance.ShiftService;
import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.document.DocumentService;
import com.admtechhub.maestrohr.employee.EmployeeService;
import com.admtechhub.maestrohr.loan.LoanService;
import com.admtechhub.maestrohr.payroll.PayrollRunService;
import com.admtechhub.maestrohr.subscription.FeatureAccessService;
import com.admtechhub.maestrohr.subscription.FeatureDisabledException;
import com.admtechhub.maestrohr.tenant.SubscriptionFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When a feature is off/unentitled, the server-rendered read pages (loans, self-loans, self-docs,
 * self-attendance) must render a locked state with NO feature data — mirroring the leave fix. Each
 * case: {@code FeatureAccessService.require} throws, the GET returns the shared feature-locked
 * fragment, and the page's data builder is never called.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeaturePageLockTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtService jwtService;
    @MockBean private FeatureAccessService featureAccessService;
    @MockBean private LoanListService loanListService;
    @MockBean private LoanSelfService loanSelfService;
    @MockBean private DocumentService documentService;
    @MockBean private AttendanceSelfService attendanceSelfService;
    @MockBean private PayrollListService payrollListService;
    @MockBean private AttendanceListService attendanceListService;
    @MockBean private ShiftService shiftService;
    // Present so the controllers construct; not reached on the locked path.
    @MockBean private LoanService loanService;
    @MockBean private AttendanceService attendanceService;
    @MockBean private EmployeeService employeeService;
    @MockBean private PayrollDetailService payrollDetailService;
    @MockBean private PayrollRunService payrollRunService;
    @MockBean private UserRepository userRepository;

    private void mockToken(String token, String role) {
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn("user@x.io");
        when(jwtService.extractTenantId(token)).thenReturn(UUID.randomUUID().toString());
        when(jwtService.extractRole(token)).thenReturn(role);
    }

    private void featureOff(SubscriptionFeature feature) {
        doThrow(new FeatureDisabledException(feature)).when(featureAccessService).require(feature);
    }

    @Test
    void loansPage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "HR_ADMIN");
        featureOff(SubscriptionFeature.LOAN_MANAGEMENT);

        mockMvc.perform(get("/htmx/loans").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(loanListService, never()).build();
    }

    @Test
    void myLoansPage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "EMPLOYEE");
        featureOff(SubscriptionFeature.LOAN_MANAGEMENT);

        mockMvc.perform(get("/htmx/loans/me").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(loanSelfService, never()).build(any(), any());
    }

    @Test
    void myDocumentsPage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "EMPLOYEE");
        featureOff(SubscriptionFeature.DOCUMENT_VAULT);

        mockMvc.perform(get("/htmx/documents").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(documentService, never()).listByEmployee(any());
    }

    @Test
    void selfAttendancePage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "EMPLOYEE");
        featureOff(SubscriptionFeature.ATTENDANCE_TRACKING);

        mockMvc.perform(get("/htmx/attendance/me").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(attendanceSelfService, never()).build(any(), any());
    }

    @Test
    void payrollPage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "HR_ADMIN");
        featureOff(SubscriptionFeature.BASIC_PAYROLL);

        mockMvc.perform(get("/htmx/payroll").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(payrollListService, never()).buildList(any(), any());
    }

    @Test
    void attendanceListPage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "HR_ADMIN"); // passes the controller's manual READ_ROLES check
        featureOff(SubscriptionFeature.ATTENDANCE_TRACKING);

        mockMvc.perform(get("/htmx/attendance").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(attendanceListService, never()).buildList(any(), any(), any());
    }

    @Test
    void shiftsPage_featureOff_locksWithoutData() throws Exception {
        mockToken("tok", "HR_ADMIN");
        featureOff(SubscriptionFeature.ATTENDANCE_TRACKING);

        mockMvc.perform(get("/htmx/shifts").header("Authorization", "Bearer tok").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not available right now")));

        verify(shiftService, never()).getShiftsForTenant();
    }
}
