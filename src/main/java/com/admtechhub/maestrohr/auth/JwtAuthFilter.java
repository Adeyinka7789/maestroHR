package com.admtechhub.maestrohr.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        String token = extractToken(request);

        if (PublicPaths.isNoTenant(path) && (token == null || !jwtService.isTokenValid(token))) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (token == null || !jwtService.isTokenValid(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            String email    = jwtService.extractEmail(token);
            String tenantId = jwtService.extractTenantId(token);
            String role     = jwtService.extractRole(token);

            // Reject tokens whose tenantId claim is missing or not a valid UUID.
            // This is an early 401 so callers get an actionable error rather than a
            // silent empty-result caused by a null TenantContext further down the chain.
            if (tenantId == null || tenantId.isBlank()) {
                log.warn("JWT rejected: missing tenantId claim for email={}", email);
                writeUnauthorized(response, "Invalid token: missing tenant context");
                return;
            }
            try {
                UUID.fromString(tenantId);
            } catch (IllegalArgumentException e) {
                log.warn("JWT rejected: malformed tenantId claim '{}' for email={}", tenantId, email);
                writeUnauthorized(response, "Invalid token: malformed tenant identifier");
                return;
            }

            TenantContext.setCurrentTenant(tenantId);
            MDC.put("tenantId", tenantId);

            // Impersonation (Feature 4): tag the request with the originating super-admin so the
            // AuditTrailInterceptor records impersonatedBy on every audit row for this session.
            // The role/tenant above are already the target user's, so authorization and RLS
            // scoping behave exactly as if the target user were signed in.
            if (jwtService.isImpersonationToken(token)) {
                request.setAttribute("impersonatedBy", jwtService.extractImpersonatedBy(token));
            }

            UserDetails userDetails = User.builder()
                    .username(email)
                    .password("")
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role))
                    .build();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
        }
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if ("maestrohr_token".equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\",\"status\":401}");
    }
}
