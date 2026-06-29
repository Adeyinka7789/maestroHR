package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.platform.LoginAttemptWrites;
import com.admtechhub.maestrohr.platform.PasswordResetTokenStore;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.subscription.TenantSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private static final UUID   USER_UUID   = UUID.randomUUID();
    private static final UUID   TENANT_UUID = UUID.randomUUID();
    private static final String EMAIL       = "test@maestrohr.com";
    private static final String HASHED      = "$2a$10$hashedvalue";
    private static final String CORRECT_PASS = "correctPassword";
    private static final String WRONG_PASS   = "wrongPassword";

    private AuthBootstrapQueries.UserAuthRow activeRow(int failedAttempts) {
        return new AuthBootstrapQueries.UserAuthRow(
                USER_UUID, TENANT_UUID, EMAIL, HASHED, "EMPLOYEE", true, failedAttempts, null);
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
        when(authBootstrapQueries.findUserByEmail(EMAIL)).thenReturn(Optional.of(activeRow(0)));
        when(passwordEncoder.matches(WRONG_PASS, HASHED)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest(WRONG_PASS)))
                .isInstanceOf(Exception.class);

        verify(loginAttemptWrites).recordFailedLogin(USER_UUID, 0);
    }

    // 12 ── correct password → loginAttemptWrites.resetFailedLogin called (clears counter)
    @Test
    void login_correctPassword_resetsFailedAttempts() {
        when(authBootstrapQueries.findUserByEmail(EMAIL)).thenReturn(Optional.of(activeRow(3)));
        when(passwordEncoder.matches(CORRECT_PASS, HASHED)).thenReturn(true);
        when(authBootstrapQueries.findTenantById(TENANT_UUID))
                .thenReturn(Optional.of(new AuthBootstrapQueries.TenantNameRow(TENANT_UUID, "Test Corp")));
        when(jwtService.generateToken(EMAIL, TENANT_UUID.toString(), "EMPLOYEE")).thenReturn("access_token");
        when(jwtService.generateRefreshToken(EMAIL, TENANT_UUID.toString())).thenReturn("refresh_token");

        authService.login(loginRequest(CORRECT_PASS));

        verify(loginAttemptWrites).resetFailedLogin(USER_UUID);
    }

    // 13 ── locked account throws before password is checked
    @Test
    void login_lockedAccount_throwsWithoutPasswordCheck() {
        AuthBootstrapQueries.UserAuthRow locked = new AuthBootstrapQueries.UserAuthRow(
                USER_UUID, TENANT_UUID, EMAIL, HASHED, "EMPLOYEE", true, 5,
                OffsetDateTime.now().plusHours(1));
        when(authBootstrapQueries.findUserByEmail(EMAIL)).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> authService.login(loginRequest(CORRECT_PASS)))
                .isInstanceOf(RuntimeException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    // 14 ── wrong current password on changePassword → throws, user is never saved
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

        verify(userRepository, never()).save(any());
    }

    // 15 ── correct current password → hash updated and user saved
    @Test
    void changePassword_correctCurrentPassword_updatesHash() {
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
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.changePassword(EMAIL, CORRECT_PASS, "newPassword123");

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        assertThat(cap.getValue().getPasswordHash()).isEqualTo(newHash);
    }
}
