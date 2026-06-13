package com.admtechhub.maestrohr;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E3 — proves PostgreSQL Row Level Security genuinely isolates tenants at the SQL
 * layer, independent of Hibernate's {@code @SQLRestriction}.
 *
 * <p><b>Why raw JDBC.</b> {@code @SQLRestriction} is a Hibernate construct — it only appends
 * a WHERE clause to Hibernate-generated SQL. This test deliberately opens its own
 * {@link DriverManager} connections that never go through Hibernate or Spring's datasource
 * proxy, so what it verifies is purely the database's RLS enforcement.
 *
 * <p><b>Why the {@code maestro_app} role.</b> PostgreSQL unconditionally bypasses RLS for
 * superusers and table owners. The app's normal datasource connects as {@code postgres}
 * (both), so RLS is inert for it — {@link #ownerConnectionBypassesRls_seesBothTenants()}
 * demonstrates exactly that. {@code maestro_app} (created in migration {@code V24}) is a
 * non-owner with {@code NOBYPASSRLS}, so the policies actually apply. That role is the real
 * enforcement boundary this test exercises.
 *
 * <p><b>Why {@code @SpringBootTest}.</b> Only to guarantee Flyway has applied {@code V24}
 * (creating the role) against the {@code .env} Postgres before the assertions run, and to
 * obtain an owner {@link DataSource} for seeding. The isolation checks themselves use
 * independent raw connections, so Spring is scaffolding, not the thing under test.
 *
 * <p><b>Why {@code training_programs}.</b> It is tenant-scoped with a single
 * {@code NULLIF(...)}-based policy (from {@code V20}) and, unlike the earlier tables
 * (users/employees/...), carries no second pre-{@code NULLIF} policy that would cast-error on
 * an empty tenant — so the "no tenant bound ⇒ zero rows" case returns cleanly. It also has no
 * foreign keys, keeping the seed to a single insert per tenant.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RawConnectionRlsIsolationTest {

    /** Spring's @Primary datasource → connects as {@code postgres} (owner/superuser, bypasses RLS). */
    @Autowired
    private DataSource ownerDataSource;

    @Autowired
    private Environment env;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID PROGRAM_A = UUID.randomUUID();
    private static final UUID PROGRAM_B = UUID.randomUUID();

    private String dbUrl;
    private String appUser;
    private String appPassword;

    /** Seed one {@code training_programs} row per tenant as the owner (RLS bypassed, so both insert). */
    @BeforeAll
    void seed() throws SQLException {
        dbUrl = env.getProperty("DB_URL");
        appUser = env.getProperty("APP_DB_USERNAME");
        appPassword = env.getProperty("APP_DB_PASSWORD");

        try (Connection owner = ownerDataSource.getConnection()) {
            // Parent tenant rows — training_programs.tenant_id has a FK to tenants(id).
            // Inserted as the owner, so RLS WITH CHECK is bypassed.
            try (PreparedStatement ps = owner.prepareStatement(
                    "INSERT INTO tenants (id, company_name, industry, company_size, subscription_expires_at) " +
                            "VALUES (?, ?, 'TECH', 'SMALL', NOW() + INTERVAL '1 year')")) {
                ps.setObject(1, TENANT_A);
                ps.setString(2, "E3 Tenant A");
                ps.executeUpdate();

                ps.setObject(1, TENANT_B);
                ps.setString(2, "E3 Tenant B");
                ps.executeUpdate();
            }

            try (PreparedStatement ps = owner.prepareStatement(
                    "INSERT INTO training_programs (id, tenant_id, title) VALUES (?, ?, ?)")) {
                ps.setObject(1, PROGRAM_A);
                ps.setObject(2, TENANT_A);
                ps.setString(3, "E3 RLS probe A");
                ps.executeUpdate();

                ps.setObject(1, PROGRAM_B);
                ps.setObject(2, TENANT_B);
                ps.setString(3, "E3 RLS probe B");
                ps.executeUpdate();
            }
        }
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (Connection owner = ownerDataSource.getConnection()) {
            // Delete children first (FK), then the parent tenants.
            try (PreparedStatement ps = owner.prepareStatement(
                    "DELETE FROM training_programs WHERE id IN (?, ?)")) {
                ps.setObject(1, PROGRAM_A);
                ps.setObject(2, PROGRAM_B);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = owner.prepareStatement(
                    "DELETE FROM tenants WHERE id IN (?, ?)")) {
                ps.setObject(1, TENANT_A);
                ps.setObject(2, TENANT_B);
                ps.executeUpdate();
            }
        }
    }

    // ── Isolation, as the RLS-subject maestro_app role ────────────────────────────────

    @Test
    void tenantA_seesOnlyOwnRow() throws SQLException {
        try (Connection c = appConnection()) {
            setTenant(c, TENANT_A.toString());
            assertTrue(rowVisible(c, PROGRAM_A), "Tenant A must see its own training_programs row");
            assertFalse(rowVisible(c, PROGRAM_B), "Tenant A must NOT see Tenant B's row (cross-tenant leak)");
        }
    }

    @Test
    void tenantB_seesOnlyOwnRow() throws SQLException {
        try (Connection c = appConnection()) {
            setTenant(c, TENANT_B.toString());
            assertTrue(rowVisible(c, PROGRAM_B), "Tenant B must see its own training_programs row");
            assertFalse(rowVisible(c, PROGRAM_A), "Tenant B must NOT see Tenant A's row (cross-tenant leak)");
        }
    }

    @Test
    void switchingTenantOnSameConnectionFlipsVisibility() throws SQLException {
        try (Connection c = appConnection()) {
            setTenant(c, TENANT_A.toString());
            assertTrue(rowVisible(c, PROGRAM_A));
            assertFalse(rowVisible(c, PROGRAM_B));

            // Same physical connection, new tenant: visibility must flip with the session var.
            setTenant(c, TENANT_B.toString());
            assertFalse(rowVisible(c, PROGRAM_A));
            assertTrue(rowVisible(c, PROGRAM_B));
        }
    }

    @Test
    void noTenantBound_seesNothing_andDoesNotError() throws SQLException {
        try (Connection c = appConnection()) {
            // Mirrors DataSourceConfig writing '' when no tenant is active. NULLIF(..., '')
            // turns it into NULL → no rows, and crucially does NOT throw a uuid cast error.
            setTenant(c, "");
            assertFalse(rowVisible(c, PROGRAM_A), "An empty tenant must expose no rows");
            assertFalse(rowVisible(c, PROGRAM_B), "An empty tenant must expose no rows");
        }
    }

    @Test
    void unrelatedTenant_seesNeitherRow() throws SQLException {
        try (Connection c = appConnection()) {
            setTenant(c, UUID.randomUUID().toString());
            assertFalse(rowVisible(c, PROGRAM_A));
            assertFalse(rowVisible(c, PROGRAM_B));
        }
    }

    // ── Contrast: the owner connection bypasses RLS entirely ──────────────────────────
    // Documents the audit finding — this is why the app's normal datasource (postgres)
    // is NOT the enforcement boundary, and why a NOBYPASSRLS role was needed.

    @Test
    void ownerConnectionBypassesRls_seesBothTenants() throws SQLException {
        try (Connection owner = ownerDataSource.getConnection()) {
            assertTrue(rowVisible(owner, PROGRAM_A),
                    "Owner/superuser bypasses RLS — sees Tenant A regardless of session var");
            assertTrue(rowVisible(owner, PROGRAM_B),
                    "Owner/superuser bypasses RLS — sees Tenant B regardless of session var");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    /** A raw connection as {@code maestro_app} — no Spring proxy, no Hibernate, no @SQLRestriction. */
    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, appUser, appPassword);
    }

    /** Session-scoped (is_local = false), so it persists for the life of this raw connection. */
    private void setTenant(Connection c, String tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }

    private boolean rowVisible(Connection c, UUID programId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM training_programs WHERE id = ?")) {
            ps.setObject(1, programId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
