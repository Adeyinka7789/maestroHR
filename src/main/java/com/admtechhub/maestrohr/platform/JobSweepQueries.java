package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Privileged cross-tenant candidate scans used by the scheduled jobs in {@code com.admtechhub.maestrohr.jobs}.
 *
 * <p>The scheduler has no tenant session, so under the RLS-enforced {@code maestro_app} role
 * all tenant-scoped queries return nothing. Every method here runs through the privileged
 * datasource so it sees all tenants. Callers bind {@link com.admtechhub.maestrohr.auth.TenantContext}
 * per-tenant before reading or writing through the normal (RLS-enforced) datasource.
 */
@Repository
public class JobSweepQueries {

    private final JdbcTemplate jdbc;

    public JobSweepQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TrialExpiryRow(UUID tenantId, int daysRemaining) {}

    public record PayrollPendingRow(UUID tenantId) {}

    public record StalePayrollRow(UUID tenantId, UUID runId, int payrollMonth, int payrollYear,
                                  LocalDateTime approvedAt) {}

    public record DisbursingUnknownRow(UUID tenantId, UUID runId, String batchReference) {}

    public record BirthdayRow(UUID tenantId, UUID employeeId, String firstName, String lastName) {}

    public record ProbationRow(UUID tenantId, UUID employeeId, String firstName, String lastName) {}

    public record InactiveUsersRow(UUID tenantId, long inactiveCount) {}

    /** Tenants in TRIALING status whose trial ends in exactly 1, 3, or 7 days from today. */
    public List<TrialExpiryRow> findTrialingTenantsExpiringSoon() {
        return jdbc.query(
                "SELECT ts.tenant_id, "
                        + "(ts.current_period_end::date - CURRENT_DATE)::int AS days_remaining "
                        + "FROM tenant_subscriptions ts "
                        + "WHERE ts.status = 'TRIALING' "
                        + "AND ts.current_period_end::date IN "
                        + "(CURRENT_DATE + 7, CURRENT_DATE + 3, CURRENT_DATE + 1)",
                (rs, n) -> new TrialExpiryRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getInt("days_remaining")));
    }

    /** Active/trialing tenants that have no finalized (non-DRAFT, non-REJECTED) payroll run for the given month+year. */
    public List<PayrollPendingRow> findTenantsWithPendingPayroll(int month, int year) {
        return jdbc.query(
                "SELECT ts.tenant_id "
                        + "FROM tenant_subscriptions ts "
                        + "WHERE ts.status IN ('ACTIVE', 'TRIALING') "
                        + "AND NOT EXISTS ("
                        + "  SELECT 1 FROM payroll_runs pr "
                        + "  WHERE pr.tenant_id = ts.tenant_id "
                        + "  AND pr.payroll_month = ? AND pr.payroll_year = ? "
                        + "  AND pr.status NOT IN ('DRAFT', 'REJECTED')"
                        + ")",
                (rs, n) -> new PayrollPendingRow(rs.getObject("tenant_id", UUID.class)),
                month, year);
    }

    /** Payroll runs in APPROVED status where approved_at is older than staleDays days. */
    public List<StalePayrollRow> findStaleApprovedPayrolls(int staleDays) {
        return jdbc.query(
                "SELECT pr.tenant_id, pr.id AS run_id, pr.payroll_month, pr.payroll_year, pr.approved_at "
                        + "FROM payroll_runs pr "
                        + "WHERE pr.status = 'APPROVED' "
                        + "AND pr.approved_at < now() - make_interval(days => ?)",
                (rs, n) -> {
                    Timestamp approvedAt = rs.getTimestamp("approved_at");
                    return new StalePayrollRow(
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("run_id", UUID.class),
                            rs.getInt("payroll_month"),
                            rs.getInt("payroll_year"),
                            approvedAt != null ? approvedAt.toLocalDateTime() : null);
                },
                staleDays);
    }

    /**
     * Payroll runs stuck in DISBURSING_UNKNOWN, across all tenants — feeds
     * DisbursementService.reconcileUnknownDisbursements. batch_reference may be null (a run
     * that reached DISBURSING_UNKNOWN without ever getting one); the caller decides how to
     * handle that case.
     */
    public List<DisbursingUnknownRow> findDisbursingUnknownRuns() {
        return jdbc.query(
                "SELECT tenant_id, id AS run_id, batch_reference "
                        + "FROM payroll_runs WHERE status = 'DISBURSING_UNKNOWN'",
                (rs, n) -> new DisbursingUnknownRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("batch_reference")));
    }

    /** Active employees across all tenants whose date_of_birth month and day match the given values. */
    public List<BirthdayRow> findBirthdaysToday(int month, int day) {
        return jdbc.query(
                "SELECT e.tenant_id, e.id AS employee_id, e.first_name, e.last_name "
                        + "FROM employees e "
                        + "WHERE EXTRACT(MONTH FROM e.date_of_birth) = ? "
                        + "AND EXTRACT(DAY FROM e.date_of_birth) = ? "
                        + "AND e.status = 'ACTIVE'",
                (rs, n) -> new BirthdayRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("first_name"),
                        rs.getString("last_name")),
                month, day);
    }

    /** Active employees whose probation_end_date is exactly the given target date. */
    public List<ProbationRow> findProbationEndingSoon(LocalDate targetDate) {
        return jdbc.query(
                "SELECT e.tenant_id, e.id AS employee_id, e.first_name, e.last_name "
                        + "FROM employees e "
                        + "WHERE e.probation_end_date = ? "
                        + "AND e.status = 'ACTIVE'",
                (rs, n) -> new ProbationRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("first_name"),
                        rs.getString("last_name")),
                java.sql.Date.valueOf(targetDate));
    }

    /** Distinct tenant IDs that have any leave balance record — used to drive the annual reset sweep. */
    public List<UUID> findAllTenantIdsInLeaveBalances() {
        return jdbc.query(
                "SELECT DISTINCT tenant_id FROM leave_balances",
                (rs, n) -> rs.getObject("tenant_id", UUID.class));
    }

    /** Per-tenant count of active users whose last_login_at is older than inactiveDays days. */
    public List<InactiveUsersRow> findTenantsWithInactiveUsers(int inactiveDays) {
        return jdbc.query(
                "SELECT tenant_id, COUNT(*) AS inactive_count "
                        + "FROM users "
                        + "WHERE is_active = true "
                        + "AND tenant_id IS NOT NULL "
                        + "AND last_login_at IS NOT NULL "
                        + "AND last_login_at < now() - make_interval(days => ?) "
                        + "GROUP BY tenant_id",
                (rs, n) -> new InactiveUsersRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getLong("inactive_count")),
                inactiveDays);
    }
}
