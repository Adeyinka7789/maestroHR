package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.JwtAuthFilter;
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ── Public API endpoints ──────────────────────────────────────
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/pricing/public",          // ← public pricing for plans page
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()

                        // ── Actuator (super admin only) ───────────────────────────────
                        .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")

                        // ── Thymeleaf UI pages (auth handled client-side via JWT) ─────
                        .requestMatchers(
                                "/employees/**", "/departments/**", "/pay-grades/**",
                                "/payroll/**", "/leave/**", "/attendance/**", "/reports/**",
                                "/subscription/**",
                                "/dashboard", "/", "/login", "/register",
                                "/css/**", "/js/**", "/images/**"
                        ).permitAll()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}