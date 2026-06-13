package com.admtechhub.maestrohr.admin;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.tenant.SubscriptionPlan;
import com.admtechhub.maestrohr.tenant.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase E4c-ii — proves the SUPER_ADMIN tenant/user CRUD writes resolve through the privileged
 * datasource. These are genuinely cross-tenant (an admin manages tenants other than their own),
 * so under the RLS-enforced {@code maestro_app} primary the scoped JPA path would reject the
 * inserts and no-op the updates. See {@link AuthServiceWriteTest} for the non-transactional /
 * cleanup conventions, which this test follows.
 */
@SpringBootTest
class AdminProvisioningWriteTest {

    @Autowired private AdminManagementController controller;
    @Autowired @Qualifier("privilegedJdbcTemplate") private JdbcTemplate privilegedJdbc;
    @Autowired private Environment env;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
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

    // ── Tenant CRUD ───────────────────────────────────────────────────────────

    @Test
    void createTenant_persistsRow() {
        Tenant created = controller.createTenant(tenantRequest("E4CII-ADM-RC-" + suffix))
                .getBody().getData();
        createdTenantIds.add(created.getId());

        assertNotNull(created.getId());
        assertEquals(1L, count("SELECT count(*) FROM tenants WHERE id = ?", created.getId()));
    }

    @Test
    void updateTenant_modifiesRow() {
        UUID tenantId = seedTenant();

        AdminManagementController.TenantRequest req = tenantRequest(null);
        req.setCompanyName("TEST-E4CII Renamed " + suffix);
        req.setActive(false);
        req.setSubscriptionPlan(SubscriptionPlan.PROFESSIONAL);

        Tenant updated = controller.updateTenant(tenantId, req).getBody().getData();
        assertEquals(tenantId, updated.getId());

        assertEquals("TEST-E4CII Renamed " + suffix, privilegedJdbc.queryForObject(
                "SELECT company_name FROM tenants WHERE id = ?", String.class, tenantId));
        assertEquals(Boolean.FALSE, privilegedJdbc.queryForObject(
                "SELECT is_active FROM tenants WHERE id = ?", Boolean.class, tenantId));
    }

    @Test
    void updateTenant_unknownId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateTenant(UUID.randomUUID(), tenantRequest(null)));
    }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    @Test
    void createUser_persistsRow() {
        UUID tenantId = seedTenant();
        String email = "e4cii-adm-user-" + suffix + "@test.local";

        User created = controller.createUser(userRequest(tenantId, email)).getBody().getData();
        assertNotNull(created.getId());
        assertEquals(1L, count(
                "SELECT count(*) FROM users WHERE id = ? AND tenant_id = ? AND email = ?",
                created.getId(), tenantId, email));
    }

    @Test
    void createUser_duplicateEmail_throws() {
        UUID tenantId = seedTenant();
        String email = "e4cii-adm-dup-" + suffix + "@test.local";
        controller.createUser(userRequest(tenantId, email));

        assertThrows(IllegalArgumentException.class,
                () -> controller.createUser(userRequest(tenantId, email)));
    }

    @Test
    void updateUser_mergesPartialChanges_andReassignsTenant() {
        UUID tenantA = seedTenant();
        UUID tenantB = seedTenant();
        String email = "e4cii-adm-move-" + suffix + "@test.local";
        UUID userId = controller.createUser(userRequest(tenantA, email)).getBody().getData().getId();

        AdminManagementController.UserRequest req = new AdminManagementController.UserRequest();
        req.setTenantId(tenantB);                 // genuinely cross-tenant move
        req.setRole(UserRole.FINANCE_OFFICER);
        req.setActive(true);
        // email/password left blank → must be preserved from the current row

        User updated = controller.updateUser(userId, req).getBody().getData();
        assertEquals(tenantB, updated.getTenantId());

        assertEquals(tenantB, privilegedJdbc.queryForObject(
                "SELECT tenant_id FROM users WHERE id = ?", UUID.class, userId));
        assertEquals("FINANCE_OFFICER", privilegedJdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, userId));
        assertEquals(email, privilegedJdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, userId),
                "blank email in the request must preserve the existing one");
    }

    // ── Contrast: enforced primary role cannot do the cross-tenant write ─────────

    @Test
    void maestroAppPrimary_boundToOtherTenant_cannotUpdateForeignTenant() throws SQLException {
        UUID target = seedTenant();

        try (Connection c = appConnection()) {
            setTenant(c, UUID.randomUUID().toString()); // some *other* tenant's session
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tenants SET company_name = 'HIJACKED' WHERE id = ?")) {
                ps.setObject(1, target);
                assertEquals(0, ps.executeUpdate(),
                        "RLS must hide another tenant's row from maestro_app — admin CRUD therefore "
                                + "runs on the privileged pool");
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private AdminManagementController.TenantRequest tenantRequest(String rcNumber) {
        AdminManagementController.TenantRequest req = new AdminManagementController.TenantRequest();
        req.setCompanyName("TEST-E4CII Admin " + suffix);
        req.setRcNumber(rcNumber);
        req.setIndustry("TEST");
        req.setCompanySize("1-10");
        req.setSubscriptionPlan(SubscriptionPlan.FREE_TRIAL);
        req.setSubscriptionExpiresAt(OffsetDateTime.now().plusDays(30));
        req.setActive(true);
        return req;
    }

    private AdminManagementController.UserRequest userRequest(UUID tenantId, String email) {
        AdminManagementController.UserRequest req = new AdminManagementController.UserRequest();
        req.setTenantId(tenantId);
        req.setEmail(email);
        req.setPassword("Password123!");
        req.setRole(UserRole.HR_ADMIN);
        req.setActive(true);
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
