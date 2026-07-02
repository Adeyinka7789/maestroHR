package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * Phase E4c-ii — privileged write-backs for the login lockout counter.
 *
 * <p>Login resolves the user across all tenants with no tenant session bound (op 1, see
 * {@link AuthBootstrapQueries}), so the matching counter writes have the same problem the
 * reads did: under the RLS-enforced {@code maestro_app} primary the scoped {@code users}
 * UPDATE matches no row and silently no-ops, leaving lockout tracking inert. Routing them
 * through the privileged (postgres) datasource fixes that.
 *
 * <p>It also fixes a second, pre-existing defect that the read flip did not: the failed-attempt
 * write previously ran inside {@code AuthService.login}'s {@code @Transactional} boundary, which
 * then threw {@code IllegalArgumentException} (an unchecked exception) to signal bad credentials
 * — rolling the increment straight back. The privileged template uses its own auto-commit
 * connection, outside that transaction, so the increment now actually persists across the throw.
 *
 * <p>Keyed on email rather than the user's primary key: one person can own more than one
 * company (one {@code users} row per tenant, sharing an email and a synced password), and a
 * lockout must apply to the person across every membership — otherwise a wrong-password guess
 * against one company's row would leave the counter on a sibling row untouched, letting an
 * attacker keep guessing indefinitely via a different tenant.
 */
@Repository
public class LoginAttemptWrites {

    /** Mirrors {@code User.incrementFailedAttempts()}: lock after this many failures. */
    static final int MAX_FAILED_ATTEMPTS = 5;
    /** Mirrors {@code User.incrementFailedAttempts()}: lock duration once the cap is hit. */
    static final int LOCK_MINUTES = 30;

    private final JdbcTemplate jdbc;

    public LoginAttemptWrites(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one failed login: increment the counter and, on hitting the cap, set the lock.
     * {@code currentAttempts} is the value read at authentication time (from {@code UserAuthRow}).
     */
    public void recordFailedLogin(String email, int currentAttempts) {
        int next = currentAttempts + 1;
        if (next >= MAX_FAILED_ATTEMPTS) {
            jdbc.update(
                    "UPDATE users SET failed_login_attempts = ?, locked_until = ? WHERE email = ?",
                    next, OffsetDateTime.now().plusMinutes(LOCK_MINUTES), email);
        } else {
            jdbc.update(
                    "UPDATE users SET failed_login_attempts = ?, locked_until = NULL WHERE email = ?",
                    next, email);
        }
    }

    /**
     * Clear the lockout state on a successful login and stamp the last-login time. (The legacy
     * code never recorded {@code last_login_at}; doing it here closes that gap on the same write.)
     */
    public void resetFailedLogin(String email) {
        jdbc.update(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL, "
                        + "last_login_at = ? WHERE email = ?",
                OffsetDateTime.now(), email);
    }
}
