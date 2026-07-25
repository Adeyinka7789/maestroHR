package com.admtechhub.maestrohr.adjustment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, UUID> {

    /** PENDING adjustments for a period — the set a run consumes (tenant-scoped by RLS). */
    List<PayrollAdjustment> findByPeriodYearAndPeriodMonthAndStatus(
            int periodYear, int periodMonth, AdjustmentStatus status);

    /** APPLIED adjustments consumed by a given run — used to reverse them on run reversal. */
    List<PayrollAdjustment> findByPayrollRunIdAndStatus(UUID payrollRunId, AdjustmentStatus status);

    /** All adjustments for a period, newest first — the management list view. */
    List<PayrollAdjustment> findByPeriodYearAndPeriodMonthOrderByCreatedAtDesc(int periodYear, int periodMonth);

    /** History for one employee, newest first. */
    List<PayrollAdjustment> findByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    /** Guards deletion of a type that has been used. */
    long countByAdjustmentTypeId(UUID adjustmentTypeId);
}
