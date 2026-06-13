package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AuthBootstrapQueries authBootstrapQueries;
    private final TenantService tenantService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(AuthRequest.Register request) {
        Tenant tenant = tenantService.registerTenant(
                request.getCompanyName(),
                request.getRcNumber(),
                request.getIndustry(),
                request.getCompanySize()
        );

        if (userRepository.existsByEmail(request.getAdminEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .tenantId(tenant.getId())
                .email(request.getAdminEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.HR_ADMIN)
                .build();

        userRepository.save(user);

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

    @Transactional
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
     * E4c-i: the login failed-attempt / reset write-backs still target the JPA {@code users}
     * table on the primary (now {@code maestro_app}) pool. Login binds no tenant session, so
     * under {@code maestro_app} the RLS-scoped {@code findById} matches no row and the write
     * is a silent no-op — lockout tracking is therefore temporarily inert under the flipped
     * role (it still works under the postgres test role, which bypasses RLS). Re-binding the
     * resolved user's tenant session before the write is deferred to E4c-ii.
     */
    private void recordFailedAttempt(AuthBootstrapQueries.UserAuthRow auth) {
        userRepository.findById(auth.id()).ifPresent(user -> {
            user.incrementFailedAttempts();
            userRepository.save(user);
        });
    }

    private void resetFailedAttempts(AuthBootstrapQueries.UserAuthRow auth) {
        userRepository.findById(auth.id()).ifPresent(user -> {
            user.resetFailedAttempts();
            userRepository.save(user);
        });
    }
}