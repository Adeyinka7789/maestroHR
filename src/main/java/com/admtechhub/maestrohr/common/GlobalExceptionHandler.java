package com.admtechhub.maestrohr.common;

import com.admtechhub.maestrohr.auth.InvalidCredentialsException;
import com.admtechhub.maestrohr.subscription.FeatureDisabledException;
import com.admtechhub.maestrohr.subscription.FeatureNotAvailableException;
import com.admtechhub.maestrohr.tenant.TenantNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Fallback for a malformed typed parameter (e.g. a non-UUID path/query value) on any
     * controller that has no local handler of its own — most HTMX controllers register their own
     * (see ExitManagementController) so this fragment-swapped page gets a clean HTML banner
     * instead of the raw JSON body this handler returns. Covers the many plain
     * {@code @PathVariable UUID id} / {@code @RequestParam} bindings across the REST API that
     * would otherwise 500 on a bad value.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch on parameter '{}': {}", ex.getName(), ex.getMessage());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error("Invalid value for '" + ex.getName() + "'"));
    }

    /**
     * Handles bean validation errors (e.g. @Valid on request bodies).
     * Returns a 400 with a map of field names → error messages so the client
     * can display exactly what needs to be corrected.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(field, message);
        });
        // Wrap the errors map in an ApiResponse so the frontend can read them.
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .message("Validation failed")
                        .data(errors)
                        .build());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(
            AuthenticationException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication failed"));
    }

    /**
     * Login-specific credential failures (bad password, unknown email, locked account). Returns
     * 401 rather than the generic 400 IllegalArgumentException gets, and is deliberately narrow
     * (only AuthService#login throws this) so Sentry can ignore it via
     * sentry.ignored-exceptions-for-type without silencing IllegalArgumentException everywhere.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(
            InvalidCredentialsException ex) {
        log.debug("Login failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        // Don't send validation errors to Sentry
        log.debug("Validation error: {}", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * State-conflict guards (e.g. approving a non-pending leave request, or one whose balance is
     * now insufficient). These are expected user-facing outcomes, not server faults: return 409
     * with the message and keep them out of Sentry, rather than letting them fall to the 500
     * catch-all below.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.debug("State conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for request {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        // Sentry auto-captures unhandled exceptions — no need to manually call captureException
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }

    @ExceptionHandler(FeatureNotAvailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleFeatureNotAvailable(
            FeatureNotAvailableException ex) {
        log.info("Feature gated (entitlement): {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(FeatureDisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleFeatureDisabled(
            FeatureDisabledException ex) {
        // Platform flag off (kill switch / rollout): the feature is unavailable regardless of
        // plan, so it reads as 404 rather than a "payment required" upsell.
        log.info("Feature gated (platform flag off): {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTenantNotFound(
            TenantNotFoundException ex) {
        log.warn("Tenant not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleMissingStaticResource(NoResourceFoundException ex) {
        log.debug("Missing static resource: {}", ex.getMessage()); // debug, not error
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body("The requested static asset does not exist.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        log.warn("Method not allowed: {} {} - supported: {}",
                ex.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ex.getMessage()));
    }
}