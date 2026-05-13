package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.tenant.Tenant;
import com.admtechhub.maestrohr.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
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
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid email or password"
                ));

        if (user.isLocked()) {
            throw new IllegalArgumentException(
                    "Account locked. Try again later"
            );
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.incrementFailedAttempts();
            userRepository.save(user);
            throw new IllegalArgumentException("Invalid email or password");
        }

        user.resetFailedAttempts();
        userRepository.save(user);

        Tenant tenant = tenantService.findById(user.getTenantId());

        String accessToken = jwtService.generateToken(
                user.getEmail(),
                user.getTenantId().toString(),
                user.getRole().name()
        );

        String refreshToken = jwtService.generateRefreshToken(
                user.getEmail(),
                user.getTenantId().toString()
        );

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .tenantId(user.getTenantId())
                .companyName(tenant.getCompanyName())
                .build();
    }
}