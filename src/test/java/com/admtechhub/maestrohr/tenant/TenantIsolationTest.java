package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.audit.AuditTrailRepository;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Hibernate {@code @SQLRestriction} tenant isolation across EmployeeRepository,
 * LeaveRequestRepository, PayrollRunRepository, and AuditTrailRepository.
 *
 * <p>Unlike {@link com.admtechhub.maestrohr.RawConnectionRlsIsolationTest} (which probes raw
 * PostgreSQL RLS), these tests exercise the full Spring/Hibernate stack: {@code TenantContext}
 * sets the ThreadLocal, the DataSource proxy writes {@code app.current_tenant} on connection
 * checkout, and Hibernate appends the {@code @SQLRestriction} WHERE clause to every query.
 *
 * <p>Seeding uses the owner DataSource (postgres superuser), which bypasses both RLS and
 * {@code @SQLRestriction} so cross-tenant rows can be inserted without interference.
 **/
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationTest {

    /** Owner/superuser DataSource — bypasses RLS; used only for seed/cleanup. */
    @Autowired
    private DataSource ownerDataSource;

    /** Privileged JDBC: separate pool connecting as postgres, bypasses @SQLRestriction and RLS. */
    @Autowired
    @Qualifier("privilegedJdbcTemplate")
    private JdbcTemplate privilegedJdbc;

    @Autowired private EmployeeRepository    employeeRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private PayrollRunRepository   payrollRunRepository;
    @Autowired private AuditTrailRepository   auditTrailRepository;

    // ── UUIDs fixed per test class run ──────────────────────────────────────────────────

    private static final UUID TENANT_A     = UUID.randomUUID();
    private static final UUID TENANT_B     = UUID.randomUUID();
    private static final UUID DEPT_A       = UUID.randomUUID();
    private static final UUID DEPT_B       = UUID.randomUUID();
    private static final UUID GRADE_A      = UUID.randomUUID();
    private static final UUID GRADE_B      = UUID.randomUUID();
    private static final UUID EMP_A        = UUID.randomUUID();
    private static final UUID EMP_B        = UUID.randomUUID();
    private static final UUID EMP_DELETED  = UUID.randomUUID();
    private static final UUID USER_A       = UUID.randomUUID();
    private static final UUID USER_B       = UUID.randomUUID();
    private static final UUID LT_A         = UUID.randomUUID();
    private static final UUID LT_B         = UUID.randomUUID();
    private static final UUID LR_A         = UUID.randomUUID();
    private static final UUID LR_B         = UUID.randomUUID();
    private static final UUID PR_A         = UUID.randomUUID();
    private static final UUID PR_B         = UUID.randomUUID();
    private static final UUID AUDIT_A      = UUID.randomUUID();
    private static final UUID AUDIT_B      = UUID.randomUUID();

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────

    @BeforeAll
    void seed() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            // 1. Tenants (parent of every tenant-scoped table)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tenants (id, company_name, industry, company_size, subscription_expires_at)" +
                    " VALUES (?, ?, 'TECH', 'SMALL', NOW() + INTERVAL '1 year')")) {
                ps.setObject(1, TENANT_A); ps.setString(2, "Isolation Tenant A"); ps.executeUpdate();
                ps.setObject(1, TENANT_B); ps.setString(2, "Isolation Tenant B"); ps.executeUpdate();
            }

            // 2. Departments (required FK on Employee, nullable = false)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO departments (id, tenant_id, name) VALUES (?, ?, ?)")) {
                ps.setObject(1, DEPT_A); ps.setObject(2, TENANT_A); ps.setString(3, "Dept A"); ps.executeUpdate();
                ps.setObject(1, DEPT_B); ps.setObject(2, TENANT_B); ps.setString(3, "Dept B"); ps.executeUpdate();
            }

            // 3. Pay grades (required FK on Employee, nullable = false)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pay_grades (id, tenant_id, name, basic_salary, housing_allowance," +
                    " transport_allowance, other_allowances, is_active) VALUES (?, ?, ?, 100000, 20000, 10000, 5000, true)")) {
                ps.setObject(1, GRADE_A); ps.setObject(2, TENANT_A); ps.setString(3, "Grade A"); ps.executeUpdate();
                ps.setObject(1, GRADE_B); ps.setObject(2, TENANT_B); ps.setString(3, "Grade B"); ps.executeUpdate();
            }

            // 4. Active employees (one per tenant)
            String empSql =
                    "INSERT INTO employees (id, tenant_id, department_id, pay_grade_id, employee_number," +
                    " first_name, last_name, email, phone, job_title, employment_type, employment_start_date," +
                    " bank_name, bank_account_number, bank_account_name, status)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, '08011111111', 'Engineer', 'FULL_TIME', '2024-01-01'," +
                    " 'GTB', '0011111111', 'Test Employee', 'ACTIVE')";
            try (PreparedStatement ps = c.prepareStatement(empSql)) {
                ps.setObject(1, EMP_A); ps.setObject(2, TENANT_A); ps.setObject(3, DEPT_A); ps.setObject(4, GRADE_A);
                ps.setString(5, "ISO-A-001"); ps.setString(6, "Alice"); ps.setString(7, "Alpha");
                ps.setString(8, "alice@iso-a.test");
                ps.executeUpdate();

                ps.setObject(1, EMP_B); ps.setObject(2, TENANT_B); ps.setObject(3, DEPT_B); ps.setObject(4, GRADE_B);
                ps.setString(5, "ISO-B-001"); ps.setString(6, "Bob"); ps.setString(7, "Beta");
                ps.setString(8, "bob@iso-b.test");
                ps.executeUpdate();
            }

            // 5. Soft-deleted employee (Tenant A) — seeded with deleted_at already set
            //    @SQLRestriction("... AND deleted_at IS NULL") hides this from JPA queries.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO employees (id, tenant_id, department_id, pay_grade_id, employee_number," +
                    " first_name, last_name, email, phone, job_title, employment_type, employment_start_date," +
                    " bank_name, bank_account_number, bank_account_name, status, deleted_at)" +
                    " VALUES (?, ?, ?, ?, 'ISO-A-DEL', 'Deleted', 'User', 'deleted@iso-a.test'," +
                    " '08022222222', 'Engineer', 'FULL_TIME', '2024-01-01', 'GTB', '0022222222'," +
                    " 'Deleted Employee', 'ACTIVE', NOW())")) {
                ps.setObject(1, EMP_DELETED); ps.setObject(2, TENANT_A);
                ps.setObject(3, DEPT_A);      ps.setObject(4, GRADE_A);
                ps.executeUpdate();
            }

            // 6. Users (required FK on PayrollRun.initiated_by)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, tenant_id, email, password_hash, role, is_active," +
                    " failed_login_attempts, has_completed_onboarding) VALUES (?, ?, ?, 'x', 'HR', true, 0, true)")) {
                ps.setObject(1, USER_A); ps.setObject(2, TENANT_A); ps.setString(3, "user-a@iso-a.test"); ps.executeUpdate();
                ps.setObject(1, USER_B); ps.setObject(2, TENANT_B); ps.setString(3, "user-b@iso-b.test"); ps.executeUpdate();
            }

            // 7. Leave types (required FK on LeaveRequest)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO leave_types (id, tenant_id, name, code, max_days_per_year," +
                    " is_paid, requires_approval, carry_over_allowed) VALUES (?, ?, ?, ?, 20, true, true, false)")) {
                ps.setObject(1, LT_A); ps.setObject(2, TENANT_A); ps.setString(3, "Annual A"); ps.setString(4, "ANN_ISO_A"); ps.executeUpdate();
                ps.setObject(1, LT_B); ps.setObject(2, TENANT_B); ps.setString(3, "Annual B"); ps.setString(4, "ANN_ISO_B"); ps.executeUpdate();
            }

            // 7. Leave requests
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO leave_requests (id, tenant_id, employee_id, leave_type_id," +
                    " start_date, end_date, days_requested, reason, status)" +
                    " VALUES (?, ?, ?, ?, '2025-01-06', '2025-01-10', 5, 'ISO Holiday', 'PENDING')")) {
                ps.setObject(1, LR_A); ps.setObject(2, TENANT_A); ps.setObject(3, EMP_A); ps.setObject(4, LT_A); ps.executeUpdate();
                ps.setObject(1, LR_B); ps.setObject(2, TENANT_B); ps.setObject(3, EMP_B); ps.setObject(4, LT_B); ps.executeUpdate();
            }

            // 8. Payroll runs (initiated_by FK → users, NOT NULL)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO payroll_runs (id, tenant_id, payroll_month, payroll_year, status," +
                    " total_gross, total_net, total_paye, total_pension_employee, total_pension_employer, total_nhf, initiated_by)" +
                    " VALUES (?, ?, 1, 2025, 'DRAFT', 0, 0, 0, 0, 0, 0, ?)")) {
                ps.setObject(1, PR_A); ps.setObject(2, TENANT_A); ps.setObject(3, USER_A); ps.executeUpdate();
                ps.setObject(1, PR_B); ps.setObject(2, TENANT_B); ps.setObject(3, USER_B); ps.executeUpdate();
            }

            // 9. Audit trail rows (tenant_id is a plain UUID column, no FK constraint)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_trail (id, tenant_id, actor_email, action, request_path, http_method, status_code)" +
                    " VALUES (?, ?, ?, 'LOGIN', '/api/auth/login', 'POST', 200)")) {
                ps.setObject(1, AUDIT_A); ps.setObject(2, TENANT_A); ps.setString(3, "alice@iso-a.test"); ps.executeUpdate();
                ps.setObject(1, AUDIT_B); ps.setObject(2, TENANT_B); ps.setString(3, "bob@iso-b.test");  ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();}

    @AfterAll
    void cleanup() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            exec(c, "DELETE FROM audit_trail    WHERE id IN (?, ?)",         AUDIT_A,   AUDIT_B);
            exec(c, "DELETE FROM leave_requests WHERE id IN (?, ?)",         LR_A,      LR_B);
            exec(c, "DELETE FROM leave_types    WHERE id IN (?, ?)",         LT_A,      LT_B);
            exec(c, "DELETE FROM payroll_runs   WHERE id IN (?, ?)",         PR_A,      PR_B);
            exec(c, "DELETE FROM users          WHERE id IN (?, ?)",         USER_A,    USER_B);
            exec(c, "DELETE FROM employees      WHERE id IN (?, ?, ?)",      EMP_A,     EMP_B,     EMP_DELETED);
            exec(c, "DELETE FROM pay_grades     WHERE id IN (?, ?)",         GRADE_A,   GRADE_B);
            exec(c, "DELETE FROM departments    WHERE id IN (?, ?)",         DEPT_A,    DEPT_B);
            exec(c, "DELETE FROM tenants        WHERE id IN (?, ?)",         TENANT_A,  TENANT_B);
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────

    @Test
    void employee_cannotSeeOtherTenantEmployees() {
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<UUID> idsA = employeeIds();
        assertTrue(idsA.contains(EMP_A),  "Tenant A must see its own employee");
        assertFalse(idsA.contains(EMP_B), "Tenant A must NOT see Tenant B's employee (cross-tenant leak)");

        TenantContext.setCurrentTenant(TENANT_B.toString());
        List<UUID> idsB = employeeIds();
        assertTrue(idsB.contains(EMP_B),  "Tenant B must see its own employee");
        assertFalse(idsB.contains(EMP_A), "Tenant B must NOT see Tenant A's employee (cross-tenant leak)");
    }

    @Test
    void leaveRequest_isolatedByTenant() {
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<UUID> idsA = leaveRequestRepository.findAll().stream().map(lr -> lr.getId()).toList();
        assertTrue(idsA.contains(LR_A),   "Tenant A must see its own leave request");
        assertFalse(idsA.contains(LR_B),  "Tenant A must NOT see Tenant B's leave request");

        TenantContext.setCurrentTenant(TENANT_B.toString());
        List<UUID> idsB = leaveRequestRepository.findAll().stream().map(lr -> lr.getId()).toList();
        assertTrue(idsB.contains(LR_B),   "Tenant B must see its own leave request");
        assertFalse(idsB.contains(LR_A),  "Tenant B must NOT see Tenant A's leave request");
    }

    @Test
    void payrollRun_isolatedByTenant() {
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<UUID> idsA = payrollRunRepository.findAll().stream().map(pr -> pr.getId()).toList();
        assertTrue(idsA.contains(PR_A),   "Tenant A must see its own payroll run");
        assertFalse(idsA.contains(PR_B),  "Tenant A must NOT see Tenant B's payroll run");

        TenantContext.setCurrentTenant(TENANT_B.toString());
        List<UUID> idsB = payrollRunRepository.findAll().stream().map(pr -> pr.getId()).toList();
        assertTrue(idsB.contains(PR_B),   "Tenant B must see its own payroll run");
        assertFalse(idsB.contains(PR_A),  "Tenant B must NOT see Tenant A's payroll run");
    }

    @Test
    void auditTrail_crossTenantReadViaPrivilegedJDBC() {
        // Privileged JDBC bypasses @SQLRestriction (raw SQL) and RLS (superuser) — sees both tenants.
        Integer privilegedCount = privilegedJdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_trail WHERE id IN (?, ?)",
                Integer.class, AUDIT_A, AUDIT_B);
        assertEquals(2, privilegedCount, "Privileged JDBC must see both tenants' audit records");

        // Normal JPA via @SQLRestriction scopes findAll() to the current tenant only.
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<UUID> idsA = auditTrailRepository.findAll().stream().map(a -> a.getId()).toList();
        assertTrue(idsA.contains(AUDIT_A),  "JPA must return Tenant A's own audit record");
        assertFalse(idsA.contains(AUDIT_B), "JPA must NOT return Tenant B's audit record for Tenant A context");
    }

    @Test
    void softDeletedRecords_hiddenFromNormalQueries() {
        // @SQLRestriction("... AND deleted_at IS NULL") hides soft-deleted rows from all JPA queries.
        TenantContext.setCurrentTenant(TENANT_A.toString());
        List<UUID> visible = employeeIds();
        assertFalse(visible.contains(EMP_DELETED), "@SQLRestriction must hide soft-deleted employee");

        // Privileged JDBC (raw SQL, no Hibernate filters) exposes the deleted row for admin trash pages.
        Integer trashedCount = privilegedJdbc.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, EMP_DELETED);
        assertEquals(1, trashedCount, "Privileged JDBC must expose soft-deleted employee (for trash/restore page)");
    }

    @Test
    void concurrentTenantRequests_noDataLeak() throws ExecutionException, InterruptedException {
        // Each CompletableFuture runs in a separate thread with its own ThreadLocal.
        // Setting TenantContext inside the lambda is safe: the DataSource proxy reads it
        // on connection checkout, so each thread's JPA query is scoped to its own tenant.
        CompletableFuture<List<UUID>> futureA = CompletableFuture.supplyAsync(() -> {
            TenantContext.setCurrentTenant(TENANT_A.toString());
            return employeeIds();
        });
        CompletableFuture<List<UUID>> futureB = CompletableFuture.supplyAsync(() -> {
            TenantContext.setCurrentTenant(TENANT_B.toString());
            return employeeIds();
        });

        List<UUID> idsA = futureA.get();
        List<UUID> idsB = futureB.get();

        assertTrue(idsA.contains(EMP_A),   "Concurrent Tenant A thread must see its own employee");
        assertFalse(idsA.contains(EMP_B),  "Concurrent Tenant A thread must NOT see Tenant B's employee");
        assertTrue(idsB.contains(EMP_B),   "Concurrent Tenant B thread must see its own employee");
        assertFalse(idsB.contains(EMP_A),  "Concurrent Tenant B thread must NOT see Tenant A's employee");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private List<UUID> employeeIds() {
        return employeeRepository.findAll().stream().map(e -> e.getId()).toList();
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
