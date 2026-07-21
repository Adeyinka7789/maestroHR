package com.admtechhub.maestrohr.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(UUID employeeId, UUID leaveTypeId, Integer year);

    java.util.List<LeaveBalance> findByYear(Integer year);

    // Backs the EMPLOYEE dashboard "My Leave Balances" section: every leave-type balance
    // the employee holds for the given year. Tenant-scoped via the @SQLRestriction on
    // LeaveBalance; the employee id is always the authenticated user's own.
    java.util.List<LeaveBalance> findByEmployeeIdAndYear(UUID employeeId, Integer year);

    /**
     * Atomically deducts {@code days} from the balance, but only while it stays non-negative
     * ({@code daysRemaining >= :days}). Returns the number of rows updated: 1 on success, 0 when
     * the balance is now insufficient. The guard lives in the WHERE clause because this is a bulk
     * UPDATE that bypasses the entity's {@code @Version} optimistic lock, so it's the only thing
     * standing between two concurrent approvals and a negative balance.
     */
    @Modifying
    @Transactional
    @Query("UPDATE LeaveBalance lb SET lb.daysTaken = lb.daysTaken + :days, " +
            "lb.daysRemaining = lb.daysRemaining - :days " +
            "WHERE lb.employee.id = :employeeId AND lb.leaveType.id = :leaveTypeId " +
            "AND lb.year = :year AND lb.daysRemaining >= :days")
    int deductLeaveDays(@Param("employeeId") UUID employeeId,
                        @Param("leaveTypeId") UUID leaveTypeId,
                        @Param("year") Integer year,
                        @Param("days") Integer days);

    /** Resets daysTaken to 0 and restores daysRemaining for all balances in the given year (within the current tenant). */
    @Modifying
    @Transactional
    @Query("UPDATE LeaveBalance lb SET lb.daysTaken = 0, " +
            "lb.daysRemaining = lb.totalDaysEntitled + lb.daysCarriedOver " +
            "WHERE lb.year = :year")
    int resetYearlyBalances(@Param("year") int year);
}
