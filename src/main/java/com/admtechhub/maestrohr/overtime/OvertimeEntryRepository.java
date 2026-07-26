package com.admtechhub.maestrohr.overtime;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
