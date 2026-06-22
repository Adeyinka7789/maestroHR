package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.JwtAuthFilter;
import com.admtechhub.maestrohr.auth.LapsedAccessFilter;
import com.admtechhub.maestrohr.auth.PublicPaths;
import com.admtechhub.maestrohr.auth.TenantValidationFilter;
import com.admtechhub.maestrohr.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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
}