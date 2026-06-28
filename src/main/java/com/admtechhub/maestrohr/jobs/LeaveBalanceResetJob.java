package com.admtechhub.maestrohr.jobs;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.leave.LeaveBalanceRepository;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Runs at 01:00 on January 1st each year and resets all leave balance records for the new year:
 * {@code daysTaken} is zeroed out and {@code daysRemaining} is restored to
 * {@code totalDaysEntitled + daysCarriedOver} so every tenant starts the year with a clean slate.
 *
 * <p>Tenant isolation: the privileged datasource provides the set of tenant IDs, then
 * {@link TenantContext} is bound per-tenant so the JPQL update only touches that tenant's rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaveBalanceResetJob {

    private final JobSweepQueries jobSweepQueries;
    private final LeaveBalanceRepository leaveBalanceRepository;

    @Scheduled(cron = "0 0 1 1 1 *")
    public void resetLeaveBalances() {
        int year = LocalDate.now().getYear();
        List<UUID> tenantIds = jobSweepQueries.findAllTenantIdsInLeaveBalances();
        if (tenantIds.isEmpty()) {
            log.info("Leave balance reset: no leave balance records found — nothing to reset");
            return;
        }
        log.info("Leave balance reset: resetting year {} balances for {} tenant(s)", year, tenantIds.size());
        int totalReset = 0;
        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.setCurrentTenant(tenantId.toString());
                int count = leaveBalanceRepository.resetYearlyBalances(year);
                log.info("Leave balance reset: tenant {} — {} balance(s) reset for year {}", tenantId, count, year);
                totalReset += count;
            } catch (Exception e) {
                log.error("Leave balance reset failed for tenant {}: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Leave balance reset complete: {} total balance(s) reset across {} tenant(s)", totalReset, tenantIds.size());
    }
}
