package com.admtechhub.maestrohr.admin;

import com.admtechhub.maestrohr.auth.JwtService;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.platform.AuditTrailWrites;
import com.admtechhub.maestrohr.platform.AuthBootstrapQueries;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Super-admin impersonation (Feature 4).
 *
 * <p><b>Start</b> ({@code /impersonate/{userId}}) is gated to SUPER_ADMIN through the existing
 * {@code /api/admin/**} rule in {@code SecurityConfig} and is called while holding the admin's own
 * token. It mints a short-lived impersonation token (20 min, no refresh) for the target user and
 * returns it plus the display fields the client needs for the swap and red banner.
 *
 * <p><b>Exit</b> ({@code /impersonate/exit}) is called while holding the <em>impersonation</em>
 * token, whose role claim is the target user's — not SUPER_ADMIN — so it cannot pass the
 * {@code /api/admin/**} gate. {@code SecurityConfig} therefore matches this exact path as
 * {@code authenticated()} ahead of the {@code /api/admin/**} rule. The endpoint only records the
 * exit audit event; the actual token restore happens client-side.
 *
 * <p>Reads go through the privileged {@link AuthBootstrapQueries} (cross-tenant, RLS-bypassing) and
 * audit rows through the privileged {@link AuditTrailWrites}, consistent with the rest of the
 * super-admin console.
 */
@RestController
@RequestMapping("/api/admin/impersonate")
@RequiredArgsConstructor
public class AdminImpersonationController {

    private final AuthBootstrapQueries authBootstrapQueries;
    private final JwtService jwtService;
    private final AuditTrailWrites auditTrailWrites;

    @PostMapping("/{userId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ImpersonationResponse>> impersonate(
            @PathVariable UUID userId, HttpServletRequest http) {

        String adminEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        AuthBootstrapQueries.UserAuthRow target = authBootstrapQueries.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Privilege-escalation hygiene: never impersonate another super-admin, and never yourself.
        if ("SUPER_ADMIN".equals(target.role())) {
            throw new IllegalArgumentException("Cannot impersonate a super-admin");
        }
        if (adminEmail.equalsIgnoreCase(target.email())) {
            throw new IllegalArgumentException("Cannot impersonate yourself");
        }

        String companyName = authBootstrapQueries.findTenantById(target.tenantId())
                .map(AuthBootstrapQueries.TenantNameRow::companyName)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found for user: " + userId));

        String token = jwtService.generateImpersonationToken(
                target.email(), target.tenantId().toString(), target.role(), adminEmail);

        // Explicit start event, tagged with the target tenant + the initiating admin. Actor here is
        // the admin (the admin token is still in the SecurityContext for this request).
        auditTrailWrites.insert(target.tenantId(), adminEmail, "IMPERSONATION_START", "user",
                userId.toString(), http.getRequestURI(), "POST", http.getRemoteAddr(), 200,
                "Impersonating " + target.email(), adminEmail);

        return ResponseEntity.ok(ApiResponse.success("Impersonation started",
                new ImpersonationResponse(token, target.email(), target.role(),
                        target.tenantId(), companyName)));
    }

    @PostMapping("/exit")
    public ResponseEntity<ApiResponse<Void>> exit(HttpServletRequest http) {
        // Only meaningful under an impersonation token. The JwtAuthFilter has populated this
        // attribute (and the SecurityContext is the impersonated user); a normal token leaves it
        // null, in which case there's nothing to exit.
        Object impersonatedBy = http.getAttribute("impersonatedBy");
        if (!(impersonatedBy instanceof String admin) || admin.isBlank()) {
            throw new IllegalArgumentException("Not in an impersonation session");
        }

        String impersonatedUser = SecurityContextHolder.getContext().getAuthentication().getName();
        UUID tenantId = parseTenant(TenantContext.getCurrentTenant());

        auditTrailWrites.insert(tenantId, impersonatedUser, "IMPERSONATION_EXIT", "user",
                null, http.getRequestURI(), "POST", http.getRemoteAddr(), 200,
                "Exited impersonation of " + impersonatedUser, admin);

        return ResponseEntity.ok(ApiResponse.success("Impersonation ended", null));
    }

    private UUID parseTenant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Payload the client needs to swap into the impersonation session and render the banner. */
    public record ImpersonationResponse(
            String token,
            String email,
            String role,
            UUID tenantId,
            String companyName) {
    }
}
