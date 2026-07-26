package com.admtechhub.maestrohr.overtime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OvertimeEntryRepository extends JpaRepository<OvertimeEntry, UUID> {

    /** All entries for a period, newest-computed first — backs the review table. */
    List<OvertimeEntry> findByPeriodYearAndPeriodMonthOrderByAmountKoboDesc(int periodYear, int periodMonth);

    /** The existing entry for an employee in a period, if any — recompute upserts onto it. */
    Optional<OvertimeEntry> findByEmployeeIdAndPeriodYearAndPeriodMonth(UUID employeeId, int periodYear, int periodMonth);

    /**
     * Approved overtime entries from the given period key onward (periodKey = year*12 + month) —
     * backs the analytics overtime-burnout indicator and per-department overtime attribution.
     */
    @Query("SELECT o FROM OvertimeEntry o WHERE o.status = com.admtechhub.maestrohr.overtime.OvertimeStatus.APPROVED " +
            "AND (o.periodYear * 12 + o.periodMonth) >= :minPeriodKey")
    List<OvertimeEntry> findApprovedSincePeriodKey(@Param("minPeriodKey") int minPeriodKey);
}
