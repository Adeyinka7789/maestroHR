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

    @Modifying
    @Transactional
    @Query("UPDATE LeaveBalance lb SET lb.daysTaken = lb.daysTaken + :days, " +
            "lb.daysRemaining = lb.daysRemaining - :days " +
            "WHERE lb.employee.id = :employeeId AND lb.leaveType.id = :leaveTypeId AND lb.year = :year")
    void deductLeaveDays(@Param("employeeId") UUID employeeId,
                         @Param("leaveTypeId") UUID leaveTypeId,
                         @Param("year") Integer year,
                         @Param("days") Integer days);
}
