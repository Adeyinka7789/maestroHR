package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.platform.DeviceBootstrapQueries;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates the {@code X-Device-Api-Key} header for requests on the device sync chain
 * ({@code /api/v1/device/**}).
 *
 * <p>The raw key is SHA-256 hashed (deterministic, enabling a single-query lookup) and
 * matched against {@code device_api_keys.api_key_hash} via the privileged datasource — which
 * bypasses RLS since no tenant context exists yet at this point in the request. On success,
 * the device's {@code tenant_id} is used to populate {@link TenantContext}, a synthetic
 * {@code ROLE_DEVICE} principal is placed in the {@link SecurityContextHolder}, and
 * {@code last_used_at} is updated as a best-effort fire-and-forget write.
 *
 * <p>Not a {@code @Component}: instantiated explicitly in the device
 * {@link com.admtechhub.maestrohr.config.SecurityConfig} bean to prevent Spring Boot from
 * registering it as a standalone servlet filter (which would run it on every request, not
 * just the device path).
 */
@RequiredArgsConstructor
@Slf4j
public class DeviceAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Device-Api-Key";

    private final DeviceBootstrapQueries deviceBootstrapQueries;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            writeUnauthorized(response, "Missing " + HEADER + " header");
            return;
        }

        String hash = sha256Hex(rawKey.trim());
        var deviceOpt = deviceBootstrapQueries.findByApiKeyHash(hash);

        if (deviceOpt.isEmpty()) {
            log.warn("Device auth rejected: unknown or inactive API key (hash prefix={})", hash.substring(0, 8));
            writeUnauthorized(response, "Invalid or inactive device API key");
            return;
        }

        var device = deviceOpt.get();
        String tenantId = device.tenantId().toString();

        TenantContext.setCurrentTenant(tenantId);
        MDC.put("tenantId", tenantId);
        MDC.put("deviceId", device.id().toString());

        // Synthetic principal — device name is informational only.
        var auth = new UsernamePasswordAuthenticationToken(
                "device:" + device.id(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Best-effort: update last_used_at without blocking the request.
        try {
            deviceBootstrapQueries.touchLastUsedAt(device.id());
        } catch (Exception e) {
            log.warn("Failed to update last_used_at for device {}: {}", device.id(), e.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
            MDC.remove("deviceId");
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\",\"status\":401}");
    }
}
