package com.admtechhub.maestrohr.retirement;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.notification.InAppNotification;
import com.admtechhub.maestrohr.notification.InAppNotificationRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRITICAL cross-tenant isolation check for {@link RetirementNotificationJob}: two tenants,
 * each with one employee crossing the same 30-day notification threshold on the same day.
 * Runs the real job end-to-end (Spring-wired, no mocks) and verifies that tenant A's HR never
 * sees tenant B's employee in a notification (and vice versa), and that
 * {@link RetirementNotificationLogRepository} reads are correctly scoped per tenant.
 *
 * <p>Mirrors {@link com.admtechhub.maestrohr.tenant.TenantIsolationTest}: this exercises the
 * full Spring/Hibernate stack (Hibernate's {@code @SQLRestriction}, scoped by
 * {@link TenantContext} via the datasource proxy), not raw PostgreSQL RLS — the test profile's
 * datasource connects as the {@code postgres} owner/superuser (see
 * {@code application-test.yml}), under which RLS is inert by definition (superusers/owners
 * always bypass RLS). Genuine DB-level RLS enforcement for a NOBYPASSRLS role is proven
 * separately by {@link com.admtechhub.maestrohr.RawConnectionRlsIsolationTest}'s pattern; this
 * test instead proves the thing the job itself could get wrong: that the per-tenant
 * {@link TenantContext} binding and the JPA/Hibernate query scoping never leak one tenant's
 * employee into another tenant's HR notification or notification-log rows.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetirementNotificationCrossTenantIsolationTest {

    @Autowired
    private DataSource ownerDataSource;

    @Autowired
    private RetirementNotificationJob retirementNotificationJob;

    @Autowired
    private RetirementNotificationLogRepository notificationLogRepository;

    @Autowired
    private InAppNotificationRepository inAppNotificationRepository;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID DEPT_A = UUID.randomUUID();
    private static final UUID DEPT_B = UUID.randomUUID();
    private static final UUID GRADE_A = UUID.randomUUID();
    private static final UUID GRADE_B = UUID.randomUUID();
    private static final UUID EMP_A = UUID.randomUUID();
    private static final UUID EMP_B = UUID.randomUUID();
    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();

    private static final String HR_EMAIL_A = "hr-retire-a@iso-a.test";
    private static final String HR_EMAIL_B = "hr-retire-b@iso-b.test";

    /**
     * Date of birth engineered so that, under the default policy (retirement age 60,
     * thresholds "180,30"), today is exactly the 30-day-before-retirement threshold date for
     * both employees — computed relative to the real system clock (the job uses the production
     * {@code Clock} bean), not a hardcoded date, so the test is stable regardless of which day
     * it runs.
     */
    private static final LocalDate DOB_CROSSING_30_DAY_THRESHOLD =
            LocalDate.now().plusDays(30).minusYears(60);

    @BeforeAll
    void seed() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tenants (id, company_name, industry, company_size, subscription_expires_at)" +
                    " VALUES (?, ?, 'TECH', 'SMALL', NOW() + INTERVAL '1 year')")) {
                ps.setObject(1, TENANT_A); ps.setString(2, "Retirement Isolation Tenant A"); ps.executeUpdate();
                ps.setObject(1, TENANT_B); ps.setString(2, "Retirement Isolation Tenant B"); ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO departments (id, tenant_id, name) VALUES (?, ?, ?)")) {
                ps.setObject(1, DEPT_A); ps.setObject(2, TENANT_A); ps.setString(3, "Retire Dept A"); ps.executeUpdate();
                ps.setObject(1, DEPT_B); ps.setObject(2, TENANT_B); ps.setString(3, "Retire Dept B"); ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pay_grades (id, tenant_id, name, basic_salary, housing_allowance," +
                    " transport_allowance, other_allowances, is_active) VALUES (?, ?, ?, 100000, 20000, 10000, 5000, true)")) {
                ps.setObject(1, GRADE_A); ps.setObject(2, TENANT_A); ps.setString(3, "Retire Grade A"); ps.executeUpdate();
                ps.setObject(1, GRADE_B); ps.setObject(2, TENANT_B); ps.setString(3, "Retire Grade B"); ps.executeUpdate();
            }

            // Distinct, unambiguous first names so notification message content can be checked
            // for cross-tenant leakage by simple substring search.
            String empSql =
                    "INSERT INTO employees (id, tenant_id, department_id, pay_grade_id, employee_number," +
                    " first_name, last_name, email, phone, date_of_birth, job_title, employment_type," +
                    " employment_start_date, bank_name, bank_account_number, bank_account_name, status)" +
                    " VALUES (?, ?, ?, ?, ?, ?, 'RetireEmployee', ?, '08033333333', ?, 'Engineer', 'FULL_TIME'," +
                    " '2015-01-01', 'GTB', '0033333333', 'Test Employee', 'ACTIVE')";
            try (PreparedStatement ps = c.prepareStatement(empSql)) {
                ps.setObject(1, EMP_A); ps.setObject(2, TENANT_A); ps.setObject(3, DEPT_A); ps.setObject(4, GRADE_A);
                ps.setString(5, "RETIRE-ISO-A-001"); ps.setString(6, "IsoRetireAlpha");
                ps.setString(7, "isoretire-alpha@iso-a.test");
                ps.setObject(8, DOB_CROSSING_30_DAY_THRESHOLD);
                ps.executeUpdate();

                ps.setObject(1, EMP_B); ps.setObject(2, TENANT_B); ps.setObject(3, DEPT_B); ps.setObject(4, GRADE_B);
                ps.setString(5, "RETIRE-ISO-B-001"); ps.setString(6, "IsoRetireBeta");
                ps.setString(7, "isoretire-beta@iso-b.test");
                ps.setObject(8, DOB_CROSSING_30_DAY_THRESHOLD);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, tenant_id, email, password_hash, role, is_active," +
                    " failed_login_attempts, has_completed_onboarding) VALUES (?, ?, ?, 'x', 'HR_ADMIN', true, 0, true)")) {
                ps.setObject(1, USER_A); ps.setObject(2, TENANT_A); ps.setString(3, HR_EMAIL_A); ps.executeUpdate();
                ps.setObject(1, USER_B); ps.setObject(2, TENANT_B); ps.setString(3, HR_EMAIL_B); ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            exec(c, "DELETE FROM in_app_notifications WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
            exec(c, "DELETE FROM retirement_notification_logs WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
            exec(c, "DELETE FROM retirement_policies WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
            exec(c, "DELETE FROM users WHERE id IN (?, ?)", USER_A, USER_B);
            exec(c, "DELETE FROM employees WHERE id IN (?, ?)", EMP_A, EMP_B);
            exec(c, "DELETE FROM pay_grades WHERE id IN (?, ?)", GRADE_A, GRADE_B);
            exec(c, "DELETE FROM departments WHERE id IN (?, ?)", DEPT_A, DEPT_B);
            exec(c, "DELETE FROM tenants WHERE id IN (?, ?)", TENANT_A, TENANT_B);
        }
    }

    @Test
    void crossTenantIsolation_notificationsAndLogsDoNotLeak() {
        // Run the real, fully Spring-wired job once — both tenants are processed in the same sweep.
        retirementNotificationJob.notifyApproachingRetirements();

        // ── 1 & 2. Notification recipients: each tenant's HR sees only its own employee ────
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<InAppNotification> notificationsA =
                inAppNotificationRepository.findTop20ByRecipientEmailOrderByCreatedAtDesc(HR_EMAIL_A);
        assertFalse(notificationsA.isEmpty(), "Tenant A's HR must have received a retirement notification");
        for (InAppNotification n : notificationsA) {
            assertTrue(n.getMessage().contains("IsoRetireAlpha"),
                    "Tenant A's notification must reference its own employee");
            assertFalse(n.getMessage().contains("IsoRetireBeta"),
                    "Tenant A's notification must NEVER reference Tenant B's employee (cross-tenant leak)");
        }

        TenantContext.setCurrentTenant(TENANT_B.toString());
        List<InAppNotification> notificationsB =
                inAppNotificationRepository.findTop20ByRecipientEmailOrderByCreatedAtDesc(HR_EMAIL_B);
        assertFalse(notificationsB.isEmpty(), "Tenant B's HR must have received a retirement notification");
        for (InAppNotification n : notificationsB) {
            assertTrue(n.getMessage().contains("IsoRetireBeta"),
                    "Tenant B's notification must reference its own employee");
            assertFalse(n.getMessage().contains("IsoRetireAlpha"),
                    "Tenant B's notification must NEVER reference Tenant A's employee (cross-tenant leak)");
        }

        // ── 3. RetirementNotificationLog reads are correctly scoped per tenant ─────────────
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<UUID> logEmployeeIdsA = notificationLogRepository.findAll().stream()
                .map(log -> log.getEmployee().getId())
                .toList();
        assertTrue(logEmployeeIdsA.contains(EMP_A), "Tenant A context must see its own retirement notification log row");
        assertFalse(logEmployeeIdsA.contains(EMP_B), "Tenant A context must NOT see Tenant B's log row (cross-tenant leak)");

        TenantContext.setCurrentTenant(TENANT_B.toString());
        List<UUID> logEmployeeIdsB = notificationLogRepository.findAll().stream()
                .map(log -> log.getEmployee().getId())
                .toList();
        assertTrue(logEmployeeIdsB.contains(EMP_B), "Tenant B context must see its own retirement notification log row");
        assertFalse(logEmployeeIdsB.contains(EMP_A), "Tenant B context must NOT see Tenant A's log row (cross-tenant leak)");

        // ── Idempotency check, free with this fixture: a second run must not double-notify ──
        TenantContext.clear();
        retirementNotificationJob.notifyApproachingRetirements();
        TenantContext.setCurrentTenant(TENANT_A.toString());
        assertEquals(1, notificationLogRepository.findAll().stream()
                        .filter(log -> log.getEmployee().getId().equals(EMP_A)).count(),
                "Second run must not create a duplicate log row for the same (employee, threshold)");
    }

    private void exec(Connection c, String sql, UUID... ids) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                ps.setObject(i + 1, ids[i]);
            }
            ps.executeUpdate();
        }
    }
}
