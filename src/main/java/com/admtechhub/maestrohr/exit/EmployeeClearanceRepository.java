package com.admtechhub.maestrohr.exit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeClearanceRepository extends JpaRepository<EmployeeClearance, UUID> {

    @Query("SELECT ec FROM EmployeeClearance ec WHERE ec.exitRequest.id = :exitRequestId")
    List<EmployeeClearance> findByExitRequestId(@Param("exitRequestId") UUID exitRequestId);

    @Query("SELECT COUNT(ec) FROM EmployeeClearance ec WHERE ec.exitRequest.id = :exitRequestId AND ec.status = 'CLEARED'")
    long countClearedByExitRequestId(@Param("exitRequestId") UUID exitRequestId);

    @Query("SELECT COUNT(ec) FROM EmployeeClearance ec WHERE ec.exitRequest.id = :exitRequestId")
    long countByExitRequestId(@Param("exitRequestId") UUID exitRequestId);
}