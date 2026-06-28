package com.admtechhub.maestrohr.auth;

import com.admtechhub.maestrohr.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

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
}