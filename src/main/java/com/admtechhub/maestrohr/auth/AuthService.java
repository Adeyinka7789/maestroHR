package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.notification.NotificationService;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.platform.LoginAttemptWrites;
import com.admtechhub.maestrohr.platform.PasswordResetTokenStore;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import com.admtechhub.maestrohr.subscription.TenantSubscription;
import com.admtechhub.maestrohr.subscription.TenantSubscriptionRepository;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    private final EmployeeRepository employeeRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
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
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(14))
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
        // Bootstrap read (op 1): resolve every tenant membership for this email, with no tenant
        // session bound. One person can own more than one company, so this can be >1 row; under
        // the RLS-enforced maestro_app primary role the scoped JPA lookup would see nothing here,
        // so this must go through the privileged datasource.
        List<AuthBootstrapQueries.UserAuthRow> rows =
                authBootstrapQueries.findAllUsersByEmail(request.getEmail());
        if (rows.isEmpty()) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // Password and lockout state are kept in sync across every membership row for an email
        // (see TenantUserWrites#updatePasswordHashByEmail / LoginAttemptWrites), so the first row
        // is representative for both checks regardless of which company it belongs to.
        AuthBootstrapQueries.UserAuthRow representative = rows.get(0);

        if (isLocked(representative)) {
            throw new InvalidCredentialsException("Account locked. Try again later");
        }

        if (!passwordEncoder.matches(request.getPassword(), representative.passwordHash())) {
            recordFailedAttempt(representative);
            throw new InvalidCredentialsException("Invalid email or password");
        }

        resetFailedAttempts(representative);

        AuthBootstrapQueries.UserAuthRow selected;
        if (rows.size() == 1) {
            selected = representative;
        } else if (request.getTenantId() != null) {
            selected = rows.stream()
                    .filter(r -> r.tenantId().equals(request.getTenantId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid company selection"));
        } else {
            // Password matched more than one company membership: let the caller choose before
            // issuing a token for any of them.
            List<AuthResponse.TenantChoice> choices = rows.stream()
                    .map(r -> new AuthResponse.TenantChoice(r.tenantId(), companyNameOf(r.tenantId())))
                    .toList();
            return AuthResponse.builder()
                    .requiresTenantSelection(true)
                    .tenants(choices)
                    .build();
        }

        String accessToken = jwtService.generateToken(
                selected.email(),
                selected.tenantId().toString(),
                selected.role()
        );

        String refreshToken = jwtService.generateRefreshToken(
                selected.email(),
                selected.tenantId().toString()
        );

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(selected.email())
                .role(selected.role())
                .tenantId(selected.tenantId())
                .companyName(companyNameOf(selected.tenantId()))
                .build();
    }

    /**
     * Create an additional company for an already-authenticated user (one person, many
     * companies): a new tenant plus a new {@code users} row (fresh id, same email and password
     * hash, {@code SYSTEM_ADMIN}) inserted atomically via {@link
     * TenantUserWrites#provisionTenantWithAdmin}, then a token scoped to the new tenant so the
     * caller lands directly in its dashboard. The caller's identity comes from the security
     * context (resolved to {@code email} by the controller), never a request param — this only
     * ever adds a company to the caller's own account.
     */
    public AuthResponse addCompany(String email, AuthRequest.AddCompany request) {
        if (request.getRcNumber() != null && !request.getRcNumber().isBlank()
                && authBootstrapQueries.existsTenantByRcNumber(request.getRcNumber())) {
            throw new IllegalArgumentException(
                    "A company with this RC number is already registered");
        }

        AuthBootstrapQueries.UserAuthRow existing = authBootstrapQueries.findUserByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Tenant tenant = Tenant.builder()
                .companyName(request.getCompanyName())
                .rcNumber(request.getRcNumber())
                .industry(request.getIndustry())
                .companySize(request.getCompanySize())
                .subscriptionPlan(SubscriptionPlan.FREE_TRIAL)
                .subscriptionExpiresAt(OffsetDateTime.now().plusDays(14))
                .isActive(true)
                .build();

        User user = User.builder()
                .email(existing.email())
                .passwordHash(existing.passwordHash())
                .role(UserRole.SYSTEM_ADMIN)
                .build();

        tenantUserWrites.provisionTenantWithAdmin(tenant, user);

        String accessToken = jwtService.generateToken(
                user.getEmail(), tenant.getId().toString(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(
                user.getEmail(), tenant.getId().toString());

        log.info("Additional company registered: {}", tenant.getCompanyName());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .tenantId(tenant.getId())
                .companyName(tenant.getCompanyName())
                .build();
    }

    /** Every company the caller belongs to, for the sidebar switcher. */
    @Transactional(readOnly = true)
    public List<AuthResponse.TenantChoice> getMyCompanies(String email) {
        return authBootstrapQueries.findAllUsersByEmail(email)
                .stream()
                .map(r -> new AuthResponse.TenantChoice(r.tenantId(), companyNameOf(r.tenantId())))
                .toList();
    }

    /**
     * Re-issues a JWT scoped to a different company, after verifying the caller actually belongs
     * to it (never trust the target tenant id alone — an email can only switch into a tenant it
     * already has a {@code users} row for).
     */
    public AuthResponse switchCompany(String email, UUID targetTenantId) {
        AuthBootstrapQueries.UserAuthRow row = authBootstrapQueries.findAllUsersByEmail(email)
                .stream()
                .filter(r -> r.tenantId().equals(targetTenantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("You don't have access to this company"));

        String accessToken = jwtService.generateToken(
                row.email(), row.tenantId().toString(), row.role());
        String refreshToken = jwtService.generateRefreshToken(
                row.email(), row.tenantId().toString());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(row.email())
                .role(row.role())
                .tenantId(row.tenantId())
                .companyName(companyNameOf(row.tenantId()))
                .build();
    }

    private String companyNameOf(UUID tenantId) {
        return authBootstrapQueries.findTenantById(tenantId)
                .map(AuthBootstrapQueries.TenantNameRow::companyName)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
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
     *
     * <p>The new hash is written via {@link TenantUserWrites#updatePasswordHashByEmail}, not a
     * save on the RLS-scoped {@code user} read above: one person's password is kept in sync
     * across every company they belong to, and the JPA path here only ever sees the row for the
     * caller's currently-bound tenant.
     */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }

        tenantUserWrites.updatePasswordHashByEmail(email, passwordEncoder.encode(newPassword));
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

        // Syncs the new hash across every company the person belongs to (see
        // changePassword's doc comment for why this isn't a lookup-then-update-by-id).
        tenantUserWrites.updatePasswordHashByEmail(
                row.userEmail(), passwordEncoder.encode(newPassword));
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
        loginAttemptWrites.recordFailedLogin(auth.email(), auth.failedLoginAttempts());
    }

    private void resetFailedAttempts(AuthBootstrapQueries.UserAuthRow auth) {
        loginAttemptWrites.resetFailedLogin(auth.email());
    }

    /**
     * Returns the current user's profile for the onboarding wizard. Requires an active tenant
     * session (called from an authenticated /api/auth/me request). firstName falls back to the
     * email local-part when no employee record exists. departmentName is populated only for
     * DEPT_MANAGER; companyName comes via the privileged datasource to avoid RLS edge cases.
     */
    @Transactional(readOnly = true)
    public MeResponse getMe(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        java.util.Optional<Employee> empOpt = employeeRepository.findByEmail(email);

        String firstName = empOpt
                .map(Employee::getFirstName)
                .orElseGet(() -> {
                    int at = email.indexOf('@');
                    return at > 0 ? email.substring(0, at) : email;
                });

        UUID tenantId = UUID.fromString(TenantContext.getCurrentTenant());
        String companyName = authBootstrapQueries.findTenantById(tenantId)
                .map(AuthBootstrapQueries.TenantNameRow::companyName)
                .orElse("");

        String departmentName = null;
        if (user.getRole() == UserRole.DEPT_MANAGER) {
            departmentName = empOpt
                    .map(e -> e.getDepartment() != null ? e.getDepartment().getName() : null)
                    .orElse(null);
        }

        Integer daysRemainingInTrial = null;
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
        if (sub != null && sub.getStatus() == SubscriptionStatus.TRIALING
                && sub.getCurrentPeriodEnd() != null) {
            long days = ChronoUnit.DAYS.between(OffsetDateTime.now(), sub.getCurrentPeriodEnd());
            daysRemainingInTrial = (int) Math.max(0, days);
        }

        return MeResponse.builder()
                .email(user.getEmail())
                .role(user.getRole().name())
                .hasCompletedOnboarding(user.isHasCompletedOnboarding())
                .firstName(firstName)
                .companyName(companyName)
                .departmentName(departmentName)
                .daysRemainingInTrial(daysRemainingInTrial)
                .build();
    }

    /** Marks the current user's onboarding tour as done (idempotent). */
    @Transactional
    public void completeOnboarding(String email) {
        userRepository.markOnboardingComplete(email);
        log.debug("Onboarding completed for user: {}", email);
    }
}
