package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real-DB proof that Phase 1 of {@link DisbursementService#disburseSalaries} genuinely commits
 * before the Paystack HTTP call, independent of whatever happens next — the crash-safety
 * guarantee the class-level javadoc claims. Before the self-invocation fix, Phase 1 and the
 * Paystack call shared one open transaction (same-bean self-invocation silently bypassed the
 * {@code @Transactional} proxy), so this guarantee was false: a failure after the HTTP call
 * would have rolled Phase 1 back too, on top of whatever the external transfer had already done.
 *
 * <p>Mocks only the external boundary ({@link PaystackClient}) via {@code @MockBean}, wired to
 * throw on {@code initiateBulkTransfer} — simulating Phase 2 failing outright. Everything else
 * (DisbursementService, DisbursementTransactionPhases, real repositories, real Postgres) is the
 * genuine Spring-wired stack. The proof itself reads back through a brand-new JDBC connection
 * from {@code ownerDataSource} — not the Hibernate session used to run the service call — so a
 * pass here reflects a real committed row, not a first-level-cache artifact.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisbursementPhaseCommitDurabilityTest {

    @Autowired
    private DataSource ownerDataSource;

    @Autowired
    private DisbursementService disbursementService;

    @MockBean
    private PaystackClient paystackClient;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DEPT = UUID.randomUUID();
    private static final UUID GRADE = UUID.randomUUID();
    private static final UUID EMPLOYEE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    @BeforeAll
    void seed() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            exec(c, "INSERT INTO tenants (id, company_name, industry, company_size, subscription_expires_at)"
                    + " VALUES (?, ?, 'TECH', 'SMALL', NOW() + INTERVAL '1 year')",
                    ps -> { ps.setObject(1, TENANT); ps.setString(2, "Disbursement Durability Tenant"); });

            exec(c, "INSERT INTO departments (id, tenant_id, name) VALUES (?, ?, ?)",
                    ps -> { ps.setObject(1, DEPT); ps.setObject(2, TENANT); ps.setString(3, "Engineering"); });

            exec(c, "INSERT INTO pay_grades (id, tenant_id, name, basic_salary, housing_allowance,"
                    + " transport_allowance, other_allowances, is_active) VALUES (?, ?, ?, 500000, 100000, 50000, 0, true)",
                    ps -> { ps.setObject(1, GRADE); ps.setObject(2, TENANT); ps.setString(3, "Grade-A"); });

            exec(c, "INSERT INTO employees (id, tenant_id, department_id, pay_grade_id, employee_number,"
                    + " first_name, last_name, email, phone, date_of_birth, job_title, employment_type,"
                    + " employment_start_date, bank_name, bank_account_number, bank_account_name, status,"
                    + " paystack_recipient_code)"
                    + " VALUES (?, ?, ?, ?, ?, 'Durability', 'Employee', ?, '08044444444', ?, 'Engineer', 'FULL_TIME',"
                    + " '2024-01-01', 'GTB', '0044444444', 'Test Employee', 'ACTIVE', 'RCP_TEST_001')",
                    ps -> {
                        ps.setObject(1, EMPLOYEE); ps.setObject(2, TENANT); ps.setObject(3, DEPT); ps.setObject(4, GRADE);
                        ps.setString(5, "DUR-001");
                        ps.setString(6, "durability-employee@iso.test");
                        ps.setObject(7, LocalDate.of(1990, 1, 1));
                    });

            exec(c, "INSERT INTO users (id, tenant_id, email, password_hash, role, is_active,"
                    + " failed_login_attempts, has_completed_onboarding) VALUES (?, ?, ?, 'x', 'HR_ADMIN', true, 0, true)",
                    ps -> { ps.setObject(1, USER); ps.setObject(2, TENANT); ps.setString(3, "hr-durability@iso.test"); });

            exec(c, "INSERT INTO payroll_runs (id, tenant_id, payroll_month, payroll_year, status, initiated_by)"
                    + " VALUES (?, ?, 6, 2027, 'APPROVED', ?)",
                    ps -> { ps.setObject(1, RUN); ps.setObject(2, TENANT); ps.setObject(3, USER); });

            exec(c, "INSERT INTO payroll_entries (id, tenant_id, payroll_run_id, employee_id, basic_salary,"
                    + " gross_salary, pension_employee, pension_employer, nhf_deduction, paye_tax, net_salary,"
                    + " days_worked, working_days)"
                    + " VALUES (?, ?, ?, ?, 500000, 650000, 52000, 65000, 12500, 0, 585500, 22, 22)",
                    ps -> { ps.setObject(1, ENTRY); ps.setObject(2, TENANT); ps.setObject(3, RUN); ps.setObject(4, EMPLOYEE); });
        }
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            exec(c, "DELETE FROM payroll_entries WHERE id = ?", ps -> ps.setObject(1, ENTRY));
            exec(c, "DELETE FROM payroll_runs WHERE id = ?", ps -> ps.setObject(1, RUN));
            exec(c, "DELETE FROM users WHERE id = ?", ps -> ps.setObject(1, USER));
            exec(c, "DELETE FROM employees WHERE id = ?", ps -> ps.setObject(1, EMPLOYEE));
            exec(c, "DELETE FROM pay_grades WHERE id = ?", ps -> ps.setObject(1, GRADE));
            exec(c, "DELETE FROM departments WHERE id = ?", ps -> ps.setObject(1, DEPT));
            exec(c, "DELETE FROM tenants WHERE id = ?", ps -> ps.setObject(1, TENANT));
        }
    }

    @Test
    void phase1Commits_evenWhenPaystackCallThrowsImmediatelyAfter() throws SQLException {
        when(paystackClient.initiateBulkTransfer(any()))
                .thenThrow(new RuntimeException("Simulated Paystack outage - unhandled by design"));

        TenantContext.setCurrentTenant(TENANT.toString());
        try {
            // Phase 2's failure here is a raw RuntimeException - not PaystackException or
            // PaystackUnknownStateException - so nothing in the orchestrator catches it. It must
            // propagate uncaught. The point of this test is what happened to Phase 1 BEFORE that.
            assertThrows(RuntimeException.class, () -> disbursementService.disburseSalaries(RUN));
        } finally {
            TenantContext.clear();
        }

        // Read back through a BRAND NEW connection - not the Hibernate session the service call
        // used - so this reflects a genuinely committed row, not an uncommitted in-memory write.
        try (Connection c = ownerDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, batch_reference FROM payroll_runs WHERE id = ?")) {
            ps.setObject(1, RUN);
            try (ResultSet rs = ps.executeQuery()) {
                assertEquals(true, rs.next(), "run must still exist");
                assertEquals("DISBURSING", rs.getString("status"),
                        "Phase 1's DISBURSING write must be durably committed even though Phase 2 threw");
                assertNotNull(rs.getString("batch_reference"),
                        "Phase 1's batch reference must be durably committed even though Phase 2 threw");
            }
        }

        try (Connection c = ownerDataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT transfer_status, transfer_reference FROM payroll_entries WHERE id = ?")) {
            ps.setObject(1, ENTRY);
            try (ResultSet rs = ps.executeQuery()) {
                assertEquals(true, rs.next(), "entry must still exist");
                assertEquals("PENDING", rs.getString("transfer_status"),
                        "Phase 1 leaves entries PENDING (with a reference) until Phase 3 confirms an outcome");
                assertNotNull(rs.getString("transfer_reference"),
                        "Phase 1's per-entry transfer reference must be durably committed");
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void exec(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        }
    }
}
