package com.admtechhub.maestrohr.auth;

import io.sentry.Sentry;
import io.sentry.protocol.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            // In Phase 2, this moves from Header to JWT claims [cite: 61, 292]
            String tenantId = request.getHeader("X-Tenant-ID");

            if (tenantId != null && !tenantId.isBlank()) {
                // 1. Set the context for Hibernate RLS [cite: 130, 186]
                TenantContext.setCurrentTenant(tenantId);

                // 2. Powerful Sentry Tracking: Tag every error with this tenant ID
                Sentry.setTag("tenant_id", tenantId);

                // 3. Optional: If you have the user email from security context,
                // link it to Sentry so you know EXACTLY which admin is seeing errors
                User sentryUser = new User();
                sentryUser.setId(tenantId); // Identifies the organization
                Sentry.setUser(sentryUser);
            }

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            // Capture any filter-level exceptions before they hit the GlobalHandler
            Sentry.captureException(e);
            throw e;
        } finally {
            // 4. Critical: Clear both contexts to prevent data leakage [cite: 132, 157]
            TenantContext.clear();
            Sentry.configureScope(scope -> scope.setUser(null));
        }
    }
}