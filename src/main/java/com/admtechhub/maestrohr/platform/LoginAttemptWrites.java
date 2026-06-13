package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <p>Keyed on the user's primary key (globally unique), so no tenant predicate is needed.
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
    public void recordFailedLogin(UUID userId, int currentAttempts) {
        int next = currentAttempts + 1;
        if (next >= MAX_FAILED_ATTEMPTS) {
            jdbc.update(
                    "UPDATE users SET failed_login_attempts = ?, locked_until = ? WHERE id = ?",
                    next, OffsetDateTime.now().plusMinutes(LOCK_MINUTES), userId);
        } else {
            jdbc.update(
                    "UPDATE users SET failed_login_attempts = ?, locked_until = NULL WHERE id = ?",
                    next, userId);
        }
    }

    /**
     * Clear the lockout state on a successful login and stamp the last-login time. (The legacy
     * code never recorded {@code last_login_at}; doing it here closes that gap on the same write.)
     */
    public void resetFailedLogin(UUID userId) {
        jdbc.update(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL, "
                        + "last_login_at = ? WHERE id = ?",
                OffsetDateTime.now(), userId);
    }
}
