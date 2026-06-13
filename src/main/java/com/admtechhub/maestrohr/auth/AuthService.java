package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.platform.LoginAttemptWrites;
import com.admtechhub.maestrohr.platform.TenantUserWrites;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

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

    private final AuthBootstrapQueries authBootstrapQueries;
    private final LoginAttemptWrites loginAttemptWrites;
    private final TenantUserWrites tenantUserWrites;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

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
                .role(UserRole.HR_ADMIN)
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
