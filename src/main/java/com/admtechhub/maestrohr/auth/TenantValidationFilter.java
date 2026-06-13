package com.admtechhub.maestrohr.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Runs after JwtAuthFilter in the Spring Security filter chain.
 * For any path that requires a tenant context, returns 403 immediately
 * if JwtAuthFilter did not populate TenantContext (e.g. missing/invalid token,
 * or a token whose tenantId claim was blank).
 *
 * Not a @Component — instantiated explicitly in SecurityConfig so Spring Boot
 * does not also register it as a standalone Servlet filter (which would cause
 * it to execute twice per request).
 */
public class TenantValidationFilter extends OncePerRequestFilter {

    // Paths exempt from tenant-context enforcement.
    // Keep in sync with SecurityConfig.permitAll() and JwtAuthFilter.isPublicPath().
    private static final Set<String> EXACT_PUBLIC = Set.of(
            "/", "/login", "/register", "/dashboard",
            "/actuator/health", "/actuator/info",
            "/api/pricing/public"
    );

    private static final Set<String> PREFIX_PUBLIC = Set.of(
            "/api/auth/",
            "/api/webhooks/",   // Paystack webhooks have no tenant context; resolved per-event instead
            "/actuator/health",
            "/actuator/info",
            "/css/",
            "/js/",
            "/images/",
            "/swagger-ui",
            "/v3/api-docs",
            "/favicon"
    );

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (TenantContext.getCurrentTenant() == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Tenant context required. Provide a valid Bearer token.\",\"status\":403}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        if (EXACT_PUBLIC.contains(path)) {
            return true;
        }
        for (String prefix : PREFIX_PUBLIC) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
