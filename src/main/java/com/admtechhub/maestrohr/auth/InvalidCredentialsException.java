package com.admtechhub.maestrohr.auth;

/**
 * Thrown by {@link AuthService#login} for a bad email/password, an unknown email, or a locked
 * account. Distinct from the generic {@link IllegalArgumentException} other AuthService methods
 * (and the rest of the codebase) still use, for two reasons: it maps to 401 rather than 400
 * (see GlobalExceptionHandler), and it is listed in {@code sentry.ignored-exceptions-for-type}
 * (application.yml) so a wrong-password attempt — an expected, user-caused outcome — never
 * reaches Sentry as an unhandled/fatal error. Scoped to login only; every other
 * IllegalArgumentException usage elsewhere is unaffected.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
