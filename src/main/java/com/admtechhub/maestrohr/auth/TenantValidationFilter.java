package com.admtechhub.maestrohr.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (PublicPaths.isNoTenant(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (TenantContext.getCurrentTenant() == null) {
            // A browser/HTMX navigation (e.g. refresh after the JWT expired) asks for
            // HTML — send it to the login page instead of dumping a raw JSON body into
            // the document. Genuine API clients (Accept: application/json) still get 403.
            String accept = request.getHeader("Accept");
            if (accept != null && accept.contains("text/html")) {
                response.sendRedirect("/login");
                return;
            }

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Tenant context required. Provide a valid Bearer token.\",\"status\":403}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
