package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody AuthRequest.Register request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Company registered successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody AuthRequest.Login request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(
                ApiResponse.success("Login successful", response));
    }

    /**
     * Begin a forgot-password flow. Always returns the same generic message regardless of whether
     * the email is registered, so the endpoint cannot be used to enumerate accounts.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody AuthRequest.ForgotPassword request) {
        authService.requestPasswordReset(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(
                "If an account exists for that email, a reset link has been sent.", null));
    }

    /** Complete a forgot-password flow with a valid token and a new password. */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody AuthRequest.ResetPassword request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success(
                "Your password has been reset. You can now sign in.", null));
    }

    /**
     * Returns the current user's profile for the onboarding wizard.
     * Requires a valid Bearer token — returns 401 if called without auth.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }
        MeResponse me = authService.getMe(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("ok", me));
    }

    /** Marks the current user's onboarding tour as completed. Idempotent. */
    @PostMapping("/onboarding/complete")
    public ResponseEntity<ApiResponse<Void>> completeOnboarding(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }
        authService.completeOnboarding(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("Onboarding completed", null));
    }

    /**
     * Adds a new company under the caller's own account (one person, many companies). Requires a
     * valid Bearer token — the new company is always attached to the authenticated caller, never
     * to an email supplied in the request body, so this cannot be used to attach a company to
     * someone else's account.
     */
    @PostMapping("/companies")
    public ResponseEntity<ApiResponse<AuthResponse>> addCompany(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody AuthRequest.AddCompany request) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }
        AuthResponse response = authService.addCompany(userDetails.getUsername(), request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Company added successfully", response));
    }

    /** Lists every company the authenticated caller belongs to, for the sidebar switcher. */
    @GetMapping("/my-companies")
    public ResponseEntity<ApiResponse<List<AuthResponse.TenantChoice>>> myCompanies(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }
        List<AuthResponse.TenantChoice> companies = authService.getMyCompanies(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success("ok", companies));
    }

    /**
     * Re-issues a JWT scoped to a different company the caller already belongs to. The caller's
     * identity comes from the security context, never the request body, so this can only switch
     * among the authenticated user's own memberships.
     */
    @PostMapping("/switch-company")
    public ResponseEntity<ApiResponse<AuthResponse>> switchCompany(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Unauthorized"));
        }
        UUID tenantId = UUID.fromString(body.get("tenantId"));
        AuthResponse response = authService.switchCompany(userDetails.getUsername(), tenantId);
        return ResponseEntity.ok(ApiResponse.success("Company switched", response));
    }
}