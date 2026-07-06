package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * CRITICAL cross-tenant isolation check for {@link DisbursementService#reconcileUnknownDisbursements}:
 * two tenants, each with one payroll run stuck in DISBURSING_UNKNOWN with its own distinct batch
 * reference, reconciled in the same sweep. Verifies each run's outcome tracks its OWN Paystack
 * verification result — not the other tenant's — and that {@link TenantContext}-bound repository
 * reads never leak one tenant's run into the other's view.
 *
 * <p>Mirrors {@link com.admtechhub.maestrohr.retirement.RetirementNotificationCrossTenantIsolationTest}:
 * runs the real, fully Spring-wired reconciler end-to-end (no mocks except the external Paystack
 * boundary), both tenants processed in the same sweep, then checks for leakage via
 * {@code @SQLRestriction}-scoped repository reads bound to each tenant in turn. Before the Fix 2
 * tenant-context fix, this job queried {@code findByStatus(DISBURSING_UNKNOWN)} with no tenant
 * bound at all — dead under RLS, seeing zero rows for every tenant, never reconciling anything.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisbursementReconciliationCrossTenantIsolationTest {

    @Autowired
    private DataSource ownerDataSource;

    @Autowired
    private DisbursementService disbursementService;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private PayrollEntryRepository payrollEntryRepository;

    @MockBean
    private PaystackClient paystackClient;

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
    private static final UUID RUN_A = UUID.randomUUID();
    private static final UUID RUN_B = UUID.randomUUID();
    private static final UUID ENTRY_A = UUID.randomUUID();
    private static final UUID ENTRY_B = UUID.randomUUID();

    private static final String BATCH_A = "BATCH-ISO-A-" + UUID.randomUUID();
    private static final String BATCH_B = "BATCH-ISO-B-" + UUID.randomUUID();

    @BeforeAll
    void seed() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            exec(c, "INSERT INTO tenants (id, company_name, industry, company_size, subscription_expires_at)"
                    + " VALUES (?, ?, 'TECH', 'SMALL', NOW() + INTERVAL '1 year')",
                    ps -> { ps.setObject(1, TENANT_A); ps.setString(2, "Disbursement Isolation Tenant A"); });
            exec(c, "INSERT INTO tenants (id, company_name, industry, company_size, subscription_expires_at)"
                    + " VALUES (?, ?, 'TECH', 'SMALL', NOW() + INTERVAL '1 year')",
                    ps -> { ps.setObject(1, TENANT_B); ps.setString(2, "Disbursement Isolation Tenant B"); });

            exec(c, "INSERT INTO departments (id, tenant_id, name) VALUES (?, ?, ?)",
                    ps -> { ps.setObject(1, DEPT_A); ps.setObject(2, TENANT_A); ps.setString(3, "Dept A"); });
            exec(c, "INSERT INTO departments (id, tenant_id, name) VALUES (?, ?, ?)",
                    ps -> { ps.setObject(1, DEPT_B); ps.setObject(2, TENANT_B); ps.setString(3, "Dept B"); });

            String gradeSql = "INSERT INTO pay_grades (id, tenant_id, name, basic_salary, housing_allowance,"
                    + " transport_allowance, other_allowances, is_active) VALUES (?, ?, ?, 500000, 100000, 50000, 0, true)";
            exec(c, gradeSql, ps -> { ps.setObject(1, GRADE_A); ps.setObject(2, TENANT_A); ps.setString(3, "Grade A"); });
            exec(c, gradeSql, ps -> { ps.setObject(1, GRADE_B); ps.setObject(2, TENANT_B); ps.setString(3, "Grade B"); });

            String empSql = "INSERT INTO employees (id, tenant_id, department_id, pay_grade_id, employee_number,"
                    + " first_name, last_name, email, phone, date_of_birth, job_title, employment_type,"
                    + " employment_start_date, bank_name, bank_account_number, bank_account_name, status,"
                    + " paystack_recipient_code)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'IsoEmployee', ?, '08055555555', '1990-01-01', 'Engineer',"
                    + " 'FULL_TIME', '2024-01-01', 'GTB', '0055555555', 'Test Employee', 'ACTIVE', ?)";
            exec(c, empSql, ps -> {
                ps.setObject(1, EMP_A); ps.setObject(2, TENANT_A); ps.setObject(3, DEPT_A); ps.setObject(4, GRADE_A);
                ps.setString(5, "DISB-ISO-A-001"); ps.setString(6, "IsoAlpha");
                ps.setString(7, "isodisb-alpha@iso-a.test"); ps.setString(8, "RCP_A");
            });
            exec(c, empSql, ps -> {
                ps.setObject(1, EMP_B); ps.setObject(2, TENANT_B); ps.setObject(3, DEPT_B); ps.setObject(4, GRADE_B);
                ps.setString(5, "DISB-ISO-B-001"); ps.setString(6, "IsoBeta");
                ps.setString(7, "isodisb-beta@iso-b.test"); ps.setString(8, "RCP_B");
            });

            String userSql = "INSERT INTO users (id, tenant_id, email, password_hash, role, is_active,"
                    + " failed_login_attempts, has_completed_onboarding) VALUES (?, ?, ?, 'x', 'HR_ADMIN', true, 0, true)";
            exec(c, userSql, ps -> { ps.setObject(1, USER_A); ps.setObject(2, TENANT_A); ps.setString(3, "hr-disb-a@iso-a.test"); });
            exec(c, userSql, ps -> { ps.setObject(1, USER_B); ps.setObject(2, TENANT_B); ps.setString(3, "hr-disb-b@iso-b.test"); });

            String runSql = "INSERT INTO payroll_runs (id, tenant_id, payroll_month, payroll_year, status,"
                    + " initiated_by, batch_reference) VALUES (?, ?, 6, 2027, 'DISBURSING_UNKNOWN', ?, ?)";
            exec(c, runSql, ps -> { ps.setObject(1, RUN_A); ps.setObject(2, TENANT_A); ps.setObject(3, USER_A); ps.setString(4, BATCH_A); });
            exec(c, runSql, ps -> { ps.setObject(1, RUN_B); ps.setObject(2, TENANT_B); ps.setObject(3, USER_B); ps.setString(4, BATCH_B); });

            String entrySql = "INSERT INTO payroll_entries (id, tenant_id, payroll_run_id, employee_id,"
                    + " basic_salary, gross_salary, pension_employee, pension_employer, nhf_deduction, paye_tax,"
                    + " net_salary, days_worked, working_days, transfer_status, transfer_reference)"
                    + " VALUES (?, ?, ?, ?, 500000, 650000, 52000, 65000, 12500, 0, 585500, 22, 22, 'PENDING', ?)";
            exec(c, entrySql, ps -> {
                ps.setObject(1, ENTRY_A); ps.setObject(2, TENANT_A); ps.setObject(3, RUN_A); ps.setObject(4, EMP_A);
                ps.setString(5, "SAL-ISO-A-REF");
            });
            exec(c, entrySql, ps -> {
                ps.setObject(1, ENTRY_B); ps.setObject(2, TENANT_B); ps.setObject(3, RUN_B); ps.setObject(4, EMP_B);
                ps.setString(5, "SAL-ISO-B-REF");
            });
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (Connection c = ownerDataSource.getConnection()) {
            exec(c, "DELETE FROM payroll_entries WHERE id IN (?, ?)", ps -> { ps.setObject(1, ENTRY_A); ps.setObject(2, ENTRY_B); });
            exec(c, "DELETE FROM payroll_runs WHERE id IN (?, ?)", ps -> { ps.setObject(1, RUN_A); ps.setObject(2, RUN_B); });
            exec(c, "DELETE FROM users WHERE id IN (?, ?)", ps -> { ps.setObject(1, USER_A); ps.setObject(2, USER_B); });
            exec(c, "DELETE FROM employees WHERE id IN (?, ?)", ps -> { ps.setObject(1, EMP_A); ps.setObject(2, EMP_B); });
            exec(c, "DELETE FROM pay_grades WHERE id IN (?, ?)", ps -> { ps.setObject(1, GRADE_A); ps.setObject(2, GRADE_B); });
            exec(c, "DELETE FROM departments WHERE id IN (?, ?)", ps -> { ps.setObject(1, DEPT_A); ps.setObject(2, DEPT_B); });
            exec(c, "DELETE FROM tenants WHERE id IN (?, ?)", ps -> { ps.setObject(1, TENANT_A); ps.setObject(2, TENANT_B); });
        }
    }

    @Test
    void crossTenantIsolation_reconciliationOutcomesDoNotLeakBetweenTenants() {
        // Tenant A's batch verifies as SUCCESS, tenant B's as FAILED - opposite outcomes so any
        // accidental swap between the two runs would be caught by the assertions below.
        when(paystackClient.verifyBulkTransfer(BATCH_A)).thenReturn(PaystackResponse.builder()
                .status(true).data(PaystackResponse.Data.builder().status("success").build()).build());
        when(paystackClient.verifyBulkTransfer(BATCH_B)).thenReturn(PaystackResponse.builder()
                .status(true).data(PaystackResponse.Data.builder().status("failed").build()).build());

        // Run the real, fully Spring-wired reconciler once - both tenants processed in one sweep.
        disbursementService.reconcileUnknownDisbursements();

        // ── Tenant A: confirmed SUCCESS -> DISBURSING, entry moves to DISBURSING ────────────
        TenantContext.setCurrentTenant(TENANT_A.toString());
        PayrollRun runA = payrollRunRepository.findById(RUN_A).orElseThrow();
        assertEquals(PayrollStatus.DISBURSING, runA.getStatus(),
                "Tenant A's run must reflect ITS OWN confirmed-success verification");
        PayrollEntry entryA = payrollEntryRepository.findByPayrollRunId(RUN_A, TENANT_A).get(0);
        assertEquals(TransferStatus.DISBURSING, entryA.getTransferStatus());

        // Tenant A's context must NOT see tenant B's run at all (Hibernate @SQLRestriction scoping).
        Optional<PayrollRun> crossReadOfB = payrollRunRepository.findById(RUN_B);
        assertTrue(crossReadOfB.isEmpty(),
                "Tenant A's context must NEVER see Tenant B's payroll run (cross-tenant leak)");

        // ── Tenant B: confirmed FAILED -> FAILED, entry reset to PENDING ────────────────────
        TenantContext.setCurrentTenant(TENANT_B.toString());
        PayrollRun runB = payrollRunRepository.findById(RUN_B).orElseThrow();
        assertEquals(PayrollStatus.FAILED, runB.getStatus(),
                "Tenant B's run must reflect ITS OWN confirmed-failed verification, not Tenant A's success");
        PayrollEntry entryB = payrollEntryRepository.findByPayrollRunId(RUN_B, TENANT_B).get(0);
        assertEquals(TransferStatus.PENDING, entryB.getTransferStatus(),
                "Tenant B's entry must be reset to PENDING for retry");
        assertNull(entryB.getTransferReference(), "Tenant B's stale transfer reference must be cleared");

        // Tenant B's context must NOT see tenant A's run.
        Optional<PayrollRun> crossReadOfA = payrollRunRepository.findById(RUN_A);
        assertTrue(crossReadOfA.isEmpty(),
                "Tenant B's context must NEVER see Tenant A's payroll run (cross-tenant leak)");
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
