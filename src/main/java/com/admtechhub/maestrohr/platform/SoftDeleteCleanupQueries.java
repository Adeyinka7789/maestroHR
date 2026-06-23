package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Privileged cross-tenant purge of soft-deleted rows past their retention window, backing
 * {@code SoftDeleteCleanupJob}. The job runs with no tenant session, so under the RLS-enforced
 * {@code maestro_app} primary a scoped delete would touch nothing (or only one tenant); routed
 * through the privileged ({@code postgres}) datasource these span every tenant in a single
 * statement, mirroring {@link SubscriptionSweepQueries} / {@link AdminStatsQueries}.
 *
 * <p>Each statement is its own auto-commit transaction. Order matters at the call site: employees
 * are purged before departments and pay_grades, because employees carry FKs to both — the parent
 * purges additionally guard with {@code NOT EXISTS (… employees …)} so a parent that a still-trashed
 * (not-yet-purged) employee references is left for a later run instead of throwing a FK violation.
 *
 * <p>The retention window is passed in days and applied via {@code make_interval(days => ?)} so the
 * cutoff is a bound parameter, never string-concatenated SQL.
 */
@Repository
public class SoftDeleteCleanupQueries {

    private final JdbcTemplate jdbc;

    public SoftDeleteCleanupQueries(@Qualifier("privilegedJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Permanently delete employees trashed longer than {@code retentionDays} ago. @return rows removed. */
    public int purgeEmployees(int retentionDays) {
        return jdbc.update(
                "DELETE FROM employees "
                        + "WHERE deleted_at IS NOT NULL AND deleted_at < now() - make_interval(days => ?)",
                retentionDays);
    }

    /**
     * Permanently delete departments trashed longer than {@code retentionDays} ago, but only once no
     * employee row (live or still-trashed) references them — so a department is never deleted out from
     * under a trashed employee that has not yet been purged. @return rows removed.
     */
    public int purgeDepartments(int retentionDays) {
        return jdbc.update(
                "DELETE FROM departments d "
                        + "WHERE d.deleted_at IS NOT NULL AND d.deleted_at < now() - make_interval(days => ?) "
                        + "AND NOT EXISTS (SELECT 1 FROM employees e WHERE e.department_id = d.id)",
                retentionDays);
    }

    /**
     * Permanently delete pay grades trashed longer than {@code retentionDays} ago, but only once no
     * employee row (live or still-trashed) references them. @return rows removed.
     */
    public int purgePayGrades(int retentionDays) {
        return jdbc.update(
                "DELETE FROM pay_grades p "
                        + "WHERE p.deleted_at IS NOT NULL AND p.deleted_at < now() - make_interval(days => ?) "
                        + "AND NOT EXISTS (SELECT 1 FROM employees e WHERE e.pay_grade_id = p.id)",
                retentionDays);
    }
}
