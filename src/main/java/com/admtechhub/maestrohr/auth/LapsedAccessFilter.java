package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.subscription.SubscriptionService;
import com.admtechhub.maestrohr.subscription.SubscriptionStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Enforces read-only access for tenants whose subscription has lapsed (status
 * {@code EXPIRED}). Runs after {@link TenantValidationFilter}, so {@link TenantContext}
 * is guaranteed populated for any path that reaches here.
 *
 * <p>When the bound tenant's subscription is EXPIRED, the account is frozen to read-only:
 * <ul>
 *   <li>Allowed: all safe reads ({@code GET}/{@code HEAD}/{@code OPTIONS}), the payment
 *       endpoints (so the tenant can pay to reactivate), Paystack webhooks, and auth.</li>
 *   <li>Blocked: every state-changing request on a business resource → HTTP 402.</li>
 * </ul>
 * A successful payment fires a {@code charge.success} webhook which flips the tenant back
 * to ACTIVE, restoring write access. Active/trialing tenants are never affected.
 *
 * <p>Not a {@code @Component} — instantiated explicitly in {@code SecurityConfig} so Spring
 * Boot does not also register it as a standalone servlet filter (double execution).
 */
public class LapsedAccessFilter extends OncePerRequestFilter {

    private final SubscriptionService subscriptionService;

    public LapsedAccessFilter(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Safe reads and self-service paths are always allowed — short-circuit before any
        // DB lookup so reads stay cheap even for lapsed tenants.
        if (isAlwaysAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            // No tenant context on a non-exempt path: TenantValidationFilter already
            // handles this case (403). Defensive pass-through.
            filterChain.doFilter(request, response);
            return;
        }

        SubscriptionStatus status = subscriptionService.getStatus(UUID.fromString(tenantId));
        if (status == SubscriptionStatus.EXPIRED) {
            response.setStatus(402); // 402 Payment Required
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Subscription expired - reactivate to continue\",\"status\":402}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Paths/methods exempt from the read-only freeze: safe HTTP methods, payment endpoints
     * (reactivation), webhooks (Paystack drives reactivation), and auth.
     */
    private boolean isAlwaysAllowed(HttpServletRequest request) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }

        return PublicPaths.isWriteExempt(request.getRequestURI());
    }
}
