package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.platform.LoginAttemptWrites;
import com.admtechhub.maestrohr.platform.PasswordResetTokenStore;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registration and login both run with no tenant session bound, so every database operation
 * here goes through the privileged datasource (the cross-tenant reads in
 * {@link AuthBootstrapQueries}, the lockout write-backs in {@link LoginAttemptWrites}, and the
 * provisioning inserts in {@link TenantUserWrites}). None touch the RLS-enforced primary pool,
 * so neither method is {@code @Transactional} — the one place atomicity is required, the
 * registration tenant+user insert, is handled inside {@link TenantUserWrites#provisionTenantWithAdmin}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Lifetime of a password-reset token. */
    private static final int RESET_TOKEN_EXPIRY_MINUTES = 60;

    private final AuthBootstrapQueries authBootstrapQueries;
    private final LoginAttemptWrites loginAttemptWrites;
    private final TenantUserWrites tenantUserWrites;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    public AuthResponse register(AuthRequest.Register request) {
        // Cross-tenant uniqueness checks (no tenant session) via the privileged datasource.
        if (request.getRcNumber() != null && !request.getRcNumber().isBlank()
                && authBootstrapQueries.existsTenantByRcNumber(request.getRcNumber())) {
            throw new IllegalArgumentException(
                    "A company with this RC number is already registered");
        }
        if (authBootstrapQueries.existsUserByEmail(request.getAdminEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        Tenant tenant = Tenant.builder()
                .companyName(request.getCompanyName())
                .rcNumber(request.getRcNumber())
                .industry(request.getIndustry())
                .companySize(request.getCompanySize())
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(30))
                .isActive(true)
                .build();

        User user = User.builder()
                .email(request.getAdminEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.SYSTEM_ADMIN)
                .build();

        // Atomically inserts the tenant then its admin user (wiring user.tenant_id to the new
        // tenant id) on the privileged datasource; both objects get their generated ids back.
        tenantUserWrites.provisionTenantWithAdmin(tenant, user);

        String accessToken = jwtService.generateToken(
                user.getEmail(),
                tenant.getId().toString(),
                user.getRole().name()
        );

        String refreshToken = jwtService.generateRefreshToken(
                user.getEmail(),
                tenant.getId().toString()
        );

        log.info("New company registered: {}", tenant.getCompanyName());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .tenantId(tenant.getId())
                .companyName(tenant.getCompanyName())
                .build();
    }

    public AuthResponse login(AuthRequest.Login request) {
        // Bootstrap read (op 1): resolve the user across all tenants with no tenant session
        // bound. Under the RLS-enforced maestro_app primary role the scoped JPA lookup would
        // return nothing here, so this must go through the privileged datasource.
        AuthBootstrapQueries.UserAuthRow auth = authBootstrapQueries
                .findUserByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (isLocked(auth)) {
            throw new IllegalArgumentException("Account locked. Try again later");
        }

        if (!passwordEncoder.matches(request.getPassword(), auth.passwordHash())) {
            recordFailedAttempt(auth);
            throw new IllegalArgumentException("Invalid email or password");
        }

        resetFailedAttempts(auth);

        // Bootstrap read (op 1b): the tenant company name for the response, again with no
        // tenant session bound.
        String companyName = authBootstrapQueries.findTenantById(auth.tenantId())
                .map(AuthBootstrapQueries.TenantNameRow::companyName)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        String accessToken = jwtService.generateToken(
                auth.email(),
                auth.tenantId().toString(),
                auth.role()
        );

        String refreshToken = jwtService.generateRefreshToken(
                auth.email(),
                auth.tenantId().toString()
        );

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(auth.email())
                .role(auth.role())
                .tenantId(auth.tenantId())
                .companyName(companyName)
                .build();
    }

    /**
     * Self-service password change for an authenticated user. Unlike {@link #register} and
     * {@link #login} — which run with no tenant session bound and so go through the privileged
     * datasource — this is invoked from a request that already carries the caller's JWT, so the
     * tenant context is set and the user's own row is visible and writable through the normal
     * RLS-enforced JPA path. Hence this method (and only this one) is {@code @Transactional}.
     *
     * <p>The caller's identity comes from the security context (resolved to {@code email} by the
     * controller), never from a param, so a user can only ever change their own password. Throws
     * {@link IllegalArgumentException} on an unknown user or a wrong current password.
     */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed for user: {}", email);
    }

    /**
     * Begin a forgot-password flow: mint a single-use, 1-hour token for the email and send the
     * reset link. Runs with no tenant session, so every step uses the privileged datasource (the
     * cross-tenant user lookup, the {@code password_reset_tokens} insert).
     *
     * <p>Deliberately silent on an unknown email: we never reveal whether an address is registered,
     * so the caller always gets the same generic response (account-enumeration defence). The token
     * is only minted/emailed when a matching user actually exists.
     */
    public void requestPasswordReset(String email) {
        var user = authBootstrapQueries.findUserByEmail(email);
        if (user.isEmpty()) {
            log.info("Password-reset requested for unknown email (no token issued)");
            return;
        }

        UUID token = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(RESET_TOKEN_EXPIRY_MINUTES);
        passwordResetTokenStore.insert(user.get().email(), token, expiresAt);

        String resetUrl = appUrl + "/reset-password?token=" + token;
        String firstName = displayName(user.get().email());
        notificationService.sendPasswordResetEmail(
                user.get().email(), firstName, resetUrl, RESET_TOKEN_EXPIRY_MINUTES);

        log.info("Password-reset token issued for an account");
    }

    /**
     * Complete a forgot-password flow: validate the token (exists, unused, not expired), set the
     * new password on the owning user and consume the token. All writes go through the privileged
     * datasource (no tenant session bound). Throws {@link IllegalArgumentException} on a missing,
     * used or expired token.
     */
    public void resetPassword(String token, String newPassword) {
        UUID tokenValue;
        try {
            tokenValue = UUID.fromString(token);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid or expired reset link.");
        }

        PasswordResetTokenStore.TokenRow row = passwordResetTokenStore.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link."));

        if (row.used() || row.isExpired()) {
            throw new IllegalArgumentException("Invalid or expired reset link.");
        }

        AuthBootstrapQueries.UserAuthRow user = authBootstrapQueries.findUserByEmail(row.userEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link."));

        tenantUserWrites.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword));
        passwordResetTokenStore.markUsed(row.id());

        log.info("Password reset completed for an account");
    }

    /** Best-effort display name for reset emails: the local part of the email address. */
    private String displayName(String email) {
        if (email == null) {
            return "there";
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private boolean isLocked(AuthBootstrapQueries.UserAuthRow auth) {
        return auth.lockedUntil() != null && auth.lockedUntil().isAfter(OffsetDateTime.now());
    }

    /**
     * Failed-attempt / reset write-backs go through the privileged datasource (E4c-ii). Login
     * binds no tenant session, so under {@code maestro_app} a scoped {@code users} write would
     * match no row and no-op; and because login throws an unchecked exception to signal bad
     * credentials, a write on the primary transaction would also be rolled back. The privileged
     * template's own auto-commit connection sidesteps both — the counter genuinely persists.
     */
    private void recordFailedAttempt(AuthBootstrapQueries.UserAuthRow auth) {
        loginAttemptWrites.recordFailedLogin(auth.id(), auth.failedLoginAttempts());
    }

    private void resetFailedAttempts(AuthBootstrapQueries.UserAuthRow auth) {
        loginAttemptWrites.resetFailedLogin(auth.id());
    }
}
