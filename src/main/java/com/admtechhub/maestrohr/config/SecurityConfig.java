package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.DeviceAuthFilter;
import com.admtechhub.maestrohr.auth.JwtAuthFilter;
import com.admtechhub.maestrohr.auth.LapsedAccessFilter;
import com.admtechhub.maestrohr.auth.PublicPaths;
import com.admtechhub.maestrohr.auth.TenantValidationFilter;
import com.admtechhub.maestrohr.platform.DeviceBootstrapQueries;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final SubscriptionService subscriptionService;

    /**
     * Device sync chain — @Order(1) so it intercepts /api/v1/device/** before the main chain.
     * Authentication is via X-Device-Api-Key (SHA-256 lookup); no JWT, no tenant pre-validation.
     * DeviceAuthFilter is instantiated here (not a @Component) to prevent Spring Boot from
     * registering it as a standalone servlet filter across all paths.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain deviceFilterChain(HttpSecurity http,
                                                 DeviceBootstrapQueries deviceBootstrapQueries) throws Exception {
        http
                .securityMatcher("/api/v1/device/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new DeviceAuthFilter(deviceBootstrapQueries),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ── Authenticated sub-paths of /api/auth/** ───────────────────
                        // These are evaluated first, before the broad /api/auth/** permitAll
                        // below, so they require a valid session even though the parent
                        // pattern is public. JwtAuthFilter still sets TenantContext for
                        // valid tokens on NO_TENANT paths, so RLS-scoped reads work here.
                        .requestMatchers("/api/auth/me", "/api/auth/onboarding/complete", "/api/auth/companies",
                                "/api/auth/my-companies", "/api/auth/switch-company")
                        .authenticated()
                        // ── Public endpoints ──────────────────────────────────────────
                        // NO_TENANT (truly public APIs + static) ∪ UI_SHELL (Thymeleaf pages,
                        // auth handled client-side via JWT). Single source of truth: PublicPaths.
                        .requestMatchers(PublicPaths.permitAllPatterns()).permitAll()

                        // ── Actuator (super admin only) ───────────────────────────────
                        .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")

                        // ── Super-admin console: cross-tenant APIs + page shells ──────
                        // These run through the privileged, RLS-bypassing datasource and/or
                        // expose every tenant's data, so they must be SUPER_ADMIN-only at the
                        // authorization layer (the client-side nav hide in layout.js is cosmetic).
                        // Impersonation exit is called while holding the impersonation token,
                        // whose role is the target user's (not SUPER_ADMIN), so it must be matched
                        // as authenticated-only BEFORE the /api/admin/** super-admin gate below.
                        .requestMatchers("/api/admin/impersonate/exit").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/htmx/admin", "/htmx/admin/**", "/htmx/subscribers", "/htmx/subscribers/**").hasRole("SUPER_ADMIN")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantValidationFilter(), JwtAuthFilter.class)
                // After tenant context is validated: freeze lapsed (EXPIRED) tenants to
                // read-only until they pay to reactivate.
                .addFilterAfter(new LapsedAccessFilter(subscriptionService), TenantValidationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(
                "ROLE_SUPER_ADMIN > ROLE_SYSTEM_ADMIN\nROLE_SYSTEM_ADMIN > ROLE_HR_ADMIN");
    }

    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}