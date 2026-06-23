package com.admtechhub.maestrohr.auth;

import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.stream.Stream;

/**
 * Single source of truth for the application's "exempt path" allowlists.
 *
 * <p>Before this class existed, the same paths were hand-maintained in four places
 * ({@code SecurityConfig.permitAll()}, {@link TenantValidationFilter},
 * {@link LapsedAccessFilter}, {@link JwtAuthFilter#isPublicPath}) using three different
 * matching styles (AntPath {@code /**}, {@code Set.contains}, {@code String.startsWith}),
 * which had drifted out of sync. Every consumer now references the groups below and matches
 * via the shared {@link AntPathMatcher}, so {@code /**} semantics are identical everywhere.
 *
 * <p>The groups are intentionally <em>distinct concerns</em>, not one flat list — a path can
 * be public to one layer and gated by another:
 * <ul>
 *   <li>{@link #NO_TENANT} — truly public: neither a JWT nor a tenant context is required.</li>
 *   <li>{@link #UI_SHELL} — {@code permitAll} at the Spring authorization layer (no role),
 *       but a tenant context (token) <em>is</em> still required, so these are deliberately
 *       <strong>not</strong> exempt from {@link TenantValidationFilter}. See note on the field.</li>
 *   <li>{@link #DOCS} — API documentation / favicon: exempt from tenant enforcement, but not
 *       part of {@code permitAll} (preserves pre-existing behavior).</li>
 *   <li>{@link #WRITE_EXEMPT} — bypasses the lapsed-tenant read-only freeze. Auth is still
 *       required; this is orthogonal to "public".</li>
 * </ul>
 */
public final class PublicPaths {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * Truly public: no JWT and no tenant context required. Exempt from both
     * {@code SecurityConfig.permitAll()} and {@link TenantValidationFilter}.
     */
    public static final List<String> NO_TENANT = List.of(
            "/api/auth/**",
            "/api/webhooks/**",          // Paystack webhooks: HMAC-verified, tenant resolved per-event
            "/api/pricing/public",
            "/actuator/health",
            "/actuator/info",
            "/css/**",
            "/js/**",
            "/images/**",
            "/",
            "/login",
            "/register",
            "/dashboard"
    );

    /**
     * Thymeleaf UI routes that are {@code permitAll} at the authorization layer (auth is handled
     * client-side via JWT) but still require a tenant context.
     *
     * <p><strong>Known issue (Phase E1, intentionally left as-is):</strong> because these are not
     * in {@link #NO_TENANT}, an <em>unauthenticated</em> request to them is rejected by
     * {@link TenantValidationFilter} with 403 rather than served as a token-less shell. In
     * practice a logged-in browser sends the {@code maestrohr_token} cookie, so the page renders.
     * Reconciling permitAll-vs-tenant for these routes is deferred to a later phase.
     */
    public static final List<String> UI_SHELL = List.of(
            "/employees/**",
            "/departments/**",
            "/pay-grades/**",
            "/payroll/**",
            "/leave/**",
            "/attendance/**",
            "/reports/**",
            "/subscription/**",
            "/attendance/devices/**"
    );

    /**
     * API docs and favicon: exempt from tenant enforcement only. Not added to
     * {@code permitAll} (they were never permitAll before), so they remain subject to the
     * authorization layer.
     */
    public static final List<String> DOCS = List.of(
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/favicon.ico"
    );

    /**
     * Device sync paths: authenticated via {@code X-Device-Api-Key} in the device
     * {@link com.admtechhub.maestrohr.config.SecurityConfig} chain (@Order(1)). Exempt from
     * JWT extraction in {@link JwtAuthFilter} (no JWT is present on device requests), and exempt
     * from {@link TenantValidationFilter} / {@link LapsedAccessFilter} (those run only in the
     * main chain which never receives these requests). Listed here so {@link #isNoTenant} returns
     * {@code true} and JwtAuthFilter skips the JWT extraction attempt rather than logging a
     * spurious "no token" path.
     */
    public static final List<String> DEVICE = List.of(
            "/api/v1/device/**"
    );

    /**
     * Paths that bypass the {@link LapsedAccessFilter} read-only freeze for EXPIRED tenants:
     * payment (so they can reactivate), webhooks (Paystack drives reactivation), and auth.
     * Authentication is still required for these — this is not a "public" list.
     */
    public static final List<String> WRITE_EXEMPT = List.of(
            "/api/payment/**",
            "/api/webhooks/**",
            "/api/auth/**"
    );

    private PublicPaths() {
    }

    /** Patterns that {@code SecurityConfig} should {@code permitAll}: {@code NO_TENANT ∪ UI_SHELL}. */
    public static String[] permitAllPatterns() {
        return Stream.concat(NO_TENANT.stream(), UI_SHELL.stream()).toArray(String[]::new);
    }

    /** True if the path requires no tenant context (exempt from {@link TenantValidationFilter}). */
    public static boolean isNoTenant(String path) {
        return matchesAny(NO_TENANT, path) || matchesAny(DOCS, path) || matchesAny(DEVICE, path);
    }

    /** True if the path bypasses the lapsed-tenant read-only freeze. */
    public static boolean isWriteExempt(String path) {
        return matchesAny(WRITE_EXEMPT, path);
    }

    private static boolean matchesAny(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
