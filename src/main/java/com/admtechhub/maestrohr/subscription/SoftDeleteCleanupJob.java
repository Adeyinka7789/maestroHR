package com.admtechhub.maestrohr.subscription;

import com.admtechhub.maestrohr.platform.SoftDeleteCleanupQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * Daily purge of soft-deleted departments, employees and pay_grades whose 90-day retention window
 * has elapsed. Trashing a row (stamping {@code deleted_at}) hides it from the application via the
 * entity {@code @SQLRestriction} and gives a 90-day grace period during which a super-admin can
 * restore it from the trash page; this job is what finally removes the rows for good.
 *
 * <p>Multi-tenant safety: the scheduler has no tenant session, so the deletes run through the
 * privileged datasource ({@link SoftDeleteCleanupQueries}) which spans every tenant in one statement
 * — no per-tenant loop is required (cf. {@link SubscriptionLapseJob}, which must bind a session per
 * tenant only because it mutates per-tenant subscription state).
 *
 * <p>Each table is purged independently and defensively: employees first (they carry FKs to the
 * other two), then the parent tables. A failure on one table is logged and does not abort the
 * others — in particular, an employee still referenced by an un-guarded child table (e.g. a
 * certification or review) will make its purge statement skip that run rather than wedge the job;
 * the parent purges' {@code NOT EXISTS} guards then simply leave such a parent for a later run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SoftDeleteCleanupJob {

    /** Soft-deleted rows older than this are permanently removed. */
    private static final int RETENTION_DAYS = 90;

    private final SoftDeleteCleanupQueries cleanupQueries;

    /** Runs at 01:30 every day (server time) — staggered after the 01:00 subscription-lapse sweep. */
    @Scheduled(cron = "0 30 1 * * *")
    public void purgeExpiredSoftDeletes() {
        // Employees first: departments / pay_grades are guarded against employees that still
        // reference them, so removing trashed employees lets more parents become purgeable.
        int employees = purge("employees", () -> cleanupQueries.purgeEmployees(RETENTION_DAYS));
        int departments = purge("departments", () -> cleanupQueries.purgeDepartments(RETENTION_DAYS));
        int payGrades = purge("pay_grades", () -> cleanupQueries.purgePayGrades(RETENTION_DAYS));

        int total = employees + departments + payGrades;
        if (total > 0) {
            log.info("Soft-delete cleanup purged {} employee(s), {} department(s), {} pay grade(s) "
                    + "past the {}-day retention window", employees, departments, payGrades, RETENTION_DAYS);
        } else {
            log.debug("Soft-delete cleanup: nothing past the {}-day retention window", RETENTION_DAYS);
        }
    }

    /** Run one table's purge, isolating any failure so the remaining tables still get swept. */
    private int purge(String table, IntSupplier op) {
        try {
            return op.getAsInt();
        } catch (Exception e) {
            log.error("Soft-delete cleanup failed for {}: {}", table, e.getMessage(), e);
            return 0;
        }
    }
}
