package com.admtechhub.maestrohr.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E4c-ii — proves the registration and login write paths work through the privileged
 * datasource now that the primary runs as the RLS-enforced {@code maestro_app} role.
 *
 * <p><b>Not {@code @Transactional}.</b> The writes under test commit on the privileged pool's
 * own connection, so they cannot be observed from (or rolled back with) a test transaction.
 * Fixtures are seeded and asserted via the privileged template and torn down in {@link #cleanup()};
 * everything is tagged {@code TEST-E4CII} so a survivor of a failed cleanup is identifiable.
 *
 * <p>Each meaningful case is paired with a raw {@code maestro_app} connection (per the chosen
 * strategy) showing the same write on the enforced primary role no-ops with no tenant bound —
 * i.e. why these paths had to move to the privileged datasource.
 */
@SpringBootTest
class AuthServiceWriteTest {

    @Autowired private AuthService authService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired @Qualifier("privilegedJdbcTemplate") private JdbcTemplate privilegedJdbc;
    @Autowired private Environment env;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String rawPassword = "Password123!";
    private final List<UUID> createdTenantIds = new ArrayList<>();

    @BeforeEach
    void clearContext() {
        TenantContext.clear();
    }

    @AfterEach
    void cleanup() {
        for (UUID tenantId : createdTenantIds) {
            privilegedJdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
            privilegedJdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
        TenantContext.clear();
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @Test
    void register_createsTenantAndAdminUser() {
        AuthRequest.Register req = registerRequest("e4cii-reg-" + suffix + "@test.local",
                "E4CII-RC-" + suffix);

        AuthResponse res = authService.register(req);
        createdTenantIds.add(res.getTenantId());

        assertNotNull(res.getTenantId());
        assertEquals("HR_ADMIN", res.getRole());
        assertEquals(req.getAdminEmail(), res.getEmail());

        assertEquals(1L, count("SELECT count(*) FROM tenants WHERE id = ?", res.getTenantId()));
        assertEquals(1L, count(
                "SELECT count(*) FROM users WHERE tenant_id = ? AND email = ? AND role = 'HR_ADMIN'",
                res.getTenantId(), req.getAdminEmail()));
    }

    @Test
    void register_duplicateRcNumber_isRejected() {
        AuthResponse first = authService.register(
                registerRequest("e4cii-rc-a-" + suffix + "@test.local", "E4CII-DUP-RC-" + suffix));
        createdTenantIds.add(first.getTenantId());

        AuthRequest.Register dup = registerRequest(
                "e4cii-rc-b-" + suffix + "@test.local", "E4CII-DUP-RC-" + suffix);
        assertThrows(IllegalArgumentException.class, () -> authService.register(dup));
    }

    @Test
    void register_duplicateEmail_isRejected() {
        String email = "e4cii-dup-email-" + suffix + "@test.local";
        AuthResponse first = authService.register(registerRequest(email, "E4CII-RC-E1-" + suffix));
        createdTenantIds.add(first.getTenantId());

        AuthRequest.Register dup = registerRequest(email, "E4CII-RC-E2-" + suffix);
        assertThrows(IllegalArgumentException.class, () -> authService.register(dup));
    }

    // ── Login lockout write-backs ───────────────────────────────────────────────

    @Test
    void login_wrongPassword_incrementsCounter_andPersistsDespiteThrow() {
        UUID tenantId = seedTenant();
        UUID userId = seedUser(tenantId, "e4cii-login-" + suffix + "@test.local", 0);

        assertThrows(IllegalArgumentException.class,
                () -> authService.login(loginRequest("e4cii-login-" + suffix + "@test.local", "wrong")));

        // The increment must survive the IllegalArgumentException login throws — the whole point
        // of routing it through the (out-of-transaction) privileged datasource.
        assertEquals(1, attempts(userId));
    }

    @Test
    void login_fiveWrongPasswords_locksAccount() {
        UUID tenantId = seedTenant();
        String email = "e4cii-lock-" + suffix + "@test.local";
        UUID userId = seedUser(tenantId, email, 0);

        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.login(loginRequest(email, "wrong")));
        }

        assertEquals(5, attempts(userId));
        OffsetDateTime lockedUntil = privilegedJdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?", OffsetDateTime.class, userId);
        assertNotNull(lockedUntil);
        assertTrue(lockedUntil.isAfter(OffsetDateTime.now()));

        // Even the correct password is refused while the lock holds.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.login(loginRequest(email, rawPassword)));
        assertTrue(ex.getMessage().toLowerCase().contains("locked"));
    }

    @Test
    void login_success_resetsCounter_andStampsLastLogin() {
        UUID tenantId = seedTenant();
        String email = "e4cii-success-" + suffix + "@test.local";
        UUID userId = seedUser(tenantId, email, 3);

        AuthResponse res = authService.login(loginRequest(email, rawPassword));
        assertEquals(email, res.getEmail());

        assertEquals(0, attempts(userId));
        assertNull(privilegedJdbc.queryForObject(
                "SELECT locked_until FROM users WHERE id = ?", OffsetDateTime.class, userId));
        assertNotNull(privilegedJdbc.queryForObject(
                "SELECT last_login_at FROM users WHERE id = ?", OffsetDateTime.class, userId),
                "successful login must stamp last_login_at");
    }

    // ── Contrast: the enforced primary role no-ops the same write ────────────────

    @Test
    void maestroAppPrimary_withNoTenantBound_cannotWriteTheCounter() throws SQLException {
        UUID tenantId = seedTenant();
        UUID userId = seedUser(tenantId, "e4cii-contrast-" + suffix + "@test.local", 0);

        try (Connection c = appConnection()) {
            setTenant(c, ""); // mirrors DataSourceConfig when no tenant is bound
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET failed_login_attempts = 99 WHERE id = ?")) {
                ps.setObject(1, userId);
                int affected = ps.executeUpdate();
                assertEquals(0, affected,
                        "RLS must hide the row from maestro_app with no tenant bound — the "
                                + "scoped write no-ops, which is why login uses the privileged pool");
            }
        }
        assertEquals(0, attempts(userId), "counter must be untouched by the no-op primary write");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private AuthRequest.Register registerRequest(String email, String rcNumber) {
        AuthRequest.Register req = new AuthRequest.Register();
        req.setCompanyName("TEST-E4CII Co " + suffix);
        req.setRcNumber(rcNumber);
        req.setIndustry("TEST");
        req.setCompanySize("1-10");
        req.setAdminEmail(email);
        req.setPassword(rawPassword);
        return req;
    }

    private AuthRequest.Login loginRequest(String email, String password) {
        AuthRequest.Login req = new AuthRequest.Login();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        privilegedJdbc.update(
                "INSERT INTO tenants (id, company_name, industry, company_size, "
                        + "subscription_plan, subscription_expires_at, is_active) "
                        + "VALUES (?, ?, 'TEST', '1-10', 'FREE_TRIAL', ?, true)",
                id, "TEST-E4CII Seed " + suffix, OffsetDateTime.now().plusDays(30));
        createdTenantIds.add(id);
        return id;
    }

    private UUID seedUser(UUID tenantId, String email, int failedAttempts) {
        UUID id = UUID.randomUUID();
        privilegedJdbc.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, role, is_active, "
                        + "failed_login_attempts) VALUES (?, ?, ?, ?, 'HR_ADMIN', true, ?)",
                id, tenantId, email, passwordEncoder.encode(rawPassword), failedAttempts);
        return id;
    }

    private int attempts(UUID userId) {
        Integer value = privilegedJdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE id = ?", Integer.class, userId);
        return value != null ? value : -1;
    }

    private long count(String sql, Object... args) {
        Long value = privilegedJdbc.queryForObject(sql, Long.class, args);
        return value != null ? value : 0L;
    }

    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(
                env.getProperty("DB_URL"),
                env.getProperty("APP_DB_USERNAME"),
                env.getProperty("APP_DB_PASSWORD"));
    }

    private void setTenant(Connection c, String tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('app.current_tenant', ?, false)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }
}
