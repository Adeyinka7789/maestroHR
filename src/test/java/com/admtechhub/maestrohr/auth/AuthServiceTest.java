package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.platform.LoginAttemptWrites;
import com.admtechhub.maestrohr.platform.PasswordResetTokenStore;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.subscription.TenantSubscriptionRepository;
import com.admtechhub.maestrohr.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AuthBootstrapQueries         authBootstrapQueries;
    @Mock private LoginAttemptWrites           loginAttemptWrites;
    @Mock private TenantUserWrites             tenantUserWrites;
    @Mock private PasswordResetTokenStore      passwordResetTokenStore;
    @Mock private UserRepository               userRepository;
    @Mock private EmployeeRepository           employeeRepository;
    @Mock private TenantSubscriptionRepository tenantSubscriptionRepository;
    @Mock private JwtService                   jwtService;
    @Mock private PasswordEncoder              passwordEncoder;
    @Mock private NotificationService          notificationService;

    @InjectMocks private AuthService authService;

    private static final UUID   USER_UUID    = UUID.randomUUID();
    private static final UUID   TENANT_UUID  = UUID.randomUUID();
    private static final UUID   TENANT2_UUID = UUID.randomUUID();
    private static final String EMAIL        = "test@maestrohr.com";
    private static final String HASHED       = "$2a$10$hashedvalue";
    private static final String CORRECT_PASS = "correctPassword";
    private static final String WRONG_PASS   = "wrongPassword";

    private AuthBootstrapQueries.UserAuthRow activeRow(int failedAttempts) {
        return new AuthBootstrapQueries.UserAuthRow(
                USER_UUID, TENANT_UUID, EMAIL, HASHED, "EMPLOYEE", true, failedAttempts, null);
    }

    private AuthBootstrapQueries.UserAuthRow rowForTenant(UUID tenantId) {
        return new AuthBootstrapQueries.UserAuthRow(
                UUID.randomUUID(), tenantId, EMAIL, HASHED, "SYSTEM_ADMIN", true, 0, null);
    }

    private AuthRequest.Login loginRequest(String password) {
        AuthRequest.Login req = new AuthRequest.Login();
        req.setEmail(EMAIL);
        req.setPassword(password);
        return req;
    }

    // 11 ── wrong password → loginAttemptWrites.recordFailedLogin called with current attempt count
    @Test
    void login_wrongPassword_recordsFailedAttempt() {
        when(authBootstrapQueries.findAllUsersByEmail(EMAIL)).thenReturn(List.of(activeRow(0)));
        when(passwordEncoder.matches(WRONG_PASS, HASHED)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest(WRONG_PASS)))
                .isInstanceOf(Exception.class);

        // Keyed by email (not user id): a lockout must apply across every company the person
        // owns, since they all share the same password.
        verify(loginAttemptWrites).recordFailedLogin(EMAIL, 0);
    }

    // 12 ── correct password → loginAttemptWrites.resetFailedLogin called (clears counter)
    @Test
    void login_correctPassword_resetsFailedAttempts() {
        when(authBootstrapQueries.findAllUsersByEmail(EMAIL)).thenReturn(List.of(activeRow(3)));
        when(passwordEncoder.matches(CORRECT_PASS, HASHED)).thenReturn(true);
        when(authBootstrapQueries.findTenantById(TENANT_UUID))
                .thenReturn(Optional.of(new AuthBootstrapQueries.TenantNameRow(TENANT_UUID, "Test Corp")));
        when(jwtService.generateToken(EMAIL, TENANT_UUID.toString(), "EMPLOYEE")).thenReturn("access_token");
        when(jwtService.generateRefreshToken(EMAIL, TENANT_UUID.toString())).thenReturn("refresh_token");

        authService.login(loginRequest(CORRECT_PASS));

        verify(loginAttemptWrites).resetFailedLogin(EMAIL);
    }

    // 13 ── locked account throws before password is checked
    @Test
    void login_lockedAccount_throwsWithoutPasswordCheck() {
        AuthBootstrapQueries.UserAuthRow locked = new AuthBootstrapQueries.UserAuthRow(
                USER_UUID, TENANT_UUID, EMAIL, HASHED, "EMPLOYEE", true, 5,
                OffsetDateTime.now().plusHours(1));
        when(authBootstrapQueries.findAllUsersByEmail(EMAIL)).thenReturn(List.of(locked));

        assertThatThrownBy(() -> authService.login(loginRequest(CORRECT_PASS)))
                .isInstanceOf(RuntimeException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    // 19 ── one email owning two companies: password match with no tenantId returns the choice
    // list instead of a token.
    @Test
    void login_multipleCompanies_noTenantId_returnsTenantChoices() {
        List<AuthBootstrapQueries.UserAuthRow> rows =
                List.of(rowForTenant(TENANT_UUID), rowForTenant(TENANT2_UUID));
        when(authBootstrapQueries.findAllUsersByEmail(EMAIL)).thenReturn(rows);
        when(passwordEncoder.matches(CORRECT_PASS, HASHED)).thenReturn(true);
        when(authBootstrapQueries.findTenantById(TENANT_UUID))
                .thenReturn(Optional.of(new AuthBootstrapQueries.TenantNameRow(TENANT_UUID, "Company A")));
        when(authBootstrapQueries.findTenantById(TENANT2_UUID))
                .thenReturn(Optional.of(new AuthBootstrapQueries.TenantNameRow(TENANT2_UUID, "Company B")));

        AuthResponse response = authService.login(loginRequest(CORRECT_PASS));

        assertThat(response.isRequiresTenantSelection()).isTrue();
        assertThat(response.getAccessToken()).isNull();
        assertThat(response.getTenants()).extracting(AuthResponse.TenantChoice::companyName)
                .containsExactlyInAnyOrder("Company A", "Company B");
    }

    // 20 ── one email owning two companies: supplying tenantId on the second call issues a token
    // scoped to that company.
    @Test
    void login_multipleCompanies_withTenantId_issuesTokenForChosenTenant() {
        List<AuthBootstrapQueries.UserAuthRow> rows =
                List.of(rowForTenant(TENANT_UUID), rowForTenant(TENANT2_UUID));
        when(authBootstrapQueries.findAllUsersByEmail(EMAIL)).thenReturn(rows);
        when(passwordEncoder.matches(CORRECT_PASS, HASHED)).thenReturn(true);
        when(authBootstrapQueries.findTenantById(TENANT2_UUID))
                .thenReturn(Optional.of(new AuthBootstrapQueries.TenantNameRow(TENANT2_UUID, "Company B")));
        when(jwtService.generateToken(eq(EMAIL), eq(TENANT2_UUID.toString()), anyString()))
                .thenReturn("access_token");
        when(jwtService.generateRefreshToken(EMAIL, TENANT2_UUID.toString())).thenReturn("refresh_token");

        AuthRequest.Login request = loginRequest(CORRECT_PASS);
        request.setTenantId(TENANT2_UUID);

        AuthResponse response = authService.login(request);

        assertThat(response.isRequiresTenantSelection()).isFalse();
        assertThat(response.getTenantId()).isEqualTo(TENANT2_UUID);
        assertThat(response.getAccessToken()).isEqualTo("access_token");
    }

    // 14 ── wrong current password on changePassword → throws, hash is never written
    @Test
    void changePassword_wrongCurrentPassword_throws() {
        User user = User.builder()
                .email(EMAIL)
                .passwordHash(HASHED)
                .failedLoginAttempts(0)
                .role(UserRole.EMPLOYEE)
                .build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(WRONG_PASS, HASHED)).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(EMAIL, WRONG_PASS, "newPassword123"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(tenantUserWrites, never()).updatePasswordHashByEmail(any(), any());
    }

    // 15 ── correct current password → hash is synced across every company via updatePasswordHashByEmail
    @Test
    void changePassword_correctCurrentPassword_updatesHashAcrossTenants() {
        String newHash = "$2a$10$newHashedValue";
        User user = User.builder()
                .email(EMAIL)
                .passwordHash(HASHED)
                .failedLoginAttempts(0)
                .role(UserRole.EMPLOYEE)
                .build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CORRECT_PASS, HASHED)).thenReturn(true);
        when(passwordEncoder.encode("newPassword123")).thenReturn(newHash);

        authService.changePassword(EMAIL, CORRECT_PASS, "newPassword123");

        verify(tenantUserWrites).updatePasswordHashByEmail(EMAIL, newHash);
    }

    // 16 ── requestPasswordReset with unknown email is silent — no token minted, no email sent
    @Test
    void requestPasswordReset_unknownEmail_isSilent() {
        when(authBootstrapQueries.findUserByEmail(EMAIL)).thenReturn(Optional.empty());

        authService.requestPasswordReset(EMAIL);

        verify(passwordResetTokenStore, never()).insert(any(), any(), any());
        verify(notificationService, never()).sendPasswordResetEmail(any(), any(), any(), anyInt());
    }

    // 17 ── resetPassword with expired token throws
    @Test
    void resetPassword_expiredToken_throws() {
        UUID tokenValue = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        PasswordResetTokenStore.TokenRow expired = new PasswordResetTokenStore.TokenRow(
                tokenId, EMAIL, tokenValue,
                OffsetDateTime.now().minusMinutes(1), false);
        when(passwordResetTokenStore.findByToken(tokenValue)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.resetPassword(tokenValue.toString(), "newPass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    // 18 ── resetPassword with valid token syncs the hash across every company for that email
    // and marks the token used
    @Test
    void resetPassword_validToken_updatesPasswordAndConsumesToken() {
        UUID tokenValue = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        PasswordResetTokenStore.TokenRow valid = new PasswordResetTokenStore.TokenRow(
                tokenId, EMAIL, tokenValue,
                OffsetDateTime.now().plusHours(1), false);
        when(passwordResetTokenStore.findByToken(tokenValue)).thenReturn(Optional.of(valid));
        when(passwordEncoder.encode("newPass123")).thenReturn("$2a$10$newHash");

        authService.resetPassword(tokenValue.toString(), "newPass123");

        verify(tenantUserWrites).updatePasswordHashByEmail(EMAIL, "$2a$10$newHash");
        verify(passwordResetTokenStore).markUsed(tokenId);
    }

    // 21 ── addCompany creates a new tenant + a new SYSTEM_ADMIN user row carrying the caller's
    // existing email/password hash, and returns a token scoped to the new tenant.
    @Test
    void addCompany_success_provisionsNewTenantForExistingUser() {
        AuthRequest.AddCompany request = new AuthRequest.AddCompany();
        request.setCompanyName("Second Co");
        request.setIndustry("Tech");
        request.setCompanySize("1-10");

        when(authBootstrapQueries.findUserByEmail(EMAIL)).thenReturn(Optional.of(activeRow(0)));
        doAnswer(invocation -> {
            Tenant t = invocation.getArgument(0);
            t.setId(TENANT2_UUID);
            return null;
        }).when(tenantUserWrites).provisionTenantWithAdmin(any(Tenant.class), any(User.class));
        when(jwtService.generateToken(EMAIL, TENANT2_UUID.toString(), "SYSTEM_ADMIN"))
                .thenReturn("access_token");
        when(jwtService.generateRefreshToken(EMAIL, TENANT2_UUID.toString()))
                .thenReturn("refresh_token");

        AuthResponse response = authService.addCompany(EMAIL, request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(tenantUserWrites).provisionTenantWithAdmin(any(Tenant.class), userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo(HASHED);
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.SYSTEM_ADMIN);
        assertThat(response.getTenantId()).isEqualTo(TENANT2_UUID);
        assertThat(response.getAccessToken()).isEqualTo("access_token");
    }

    // 22 ── addCompany rejects a duplicate RC number without touching provisioning
    @Test
    void addCompany_duplicateRcNumber_throwsWithoutProvisioning() {
        AuthRequest.AddCompany request = new AuthRequest.AddCompany();
        request.setCompanyName("Second Co");
        request.setRcNumber("RC123");
        request.setIndustry("Tech");
        request.setCompanySize("1-10");

        when(authBootstrapQueries.existsTenantByRcNumber("RC123")).thenReturn(true);

        assertThatThrownBy(() -> authService.addCompany(EMAIL, request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(tenantUserWrites, never()).provisionTenantWithAdmin(any(), any());
    }
}
