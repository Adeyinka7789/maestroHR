package com.admtechhub.maestrohr.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findByEmployeeIdAndAttendanceDate(UUID employeeId, LocalDate date);

    List<AttendanceRecord> findByEmployeeIdAndAttendanceDateBetween(UUID employeeId, LocalDate startDate, LocalDate endDate);

    List<AttendanceRecord> findByAttendanceDate(LocalDate date);

    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.employee.id = :employeeId " +
            "AND a.attendanceDate BETWEEN :startDate AND :endDate " +
            "AND a.status = 'PRESENT'")
    long countPresentDays(@Param("employeeId") UUID employeeId,
                          @Param("startDate") LocalDate startDate,
                          @Param("endDate") LocalDate endDate);

    @Query("SELECT a FROM AttendanceRecord a WHERE a.attendanceDate = :date AND a.status = 'ABSENT'")
    List<AttendanceRecord> findAbsenteesOnDate(@Param("date") LocalDate date);
}