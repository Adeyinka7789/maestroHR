package com.admtechhub.maestrohr.exit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinalSettlementRepository extends JpaRepository<FinalSettlement, UUID> {

    @Query("SELECT f FROM FinalSettlement f WHERE f.exitRequest.id = :exitRequestId")
    Optional<FinalSettlement> findByExitRequestId(@Param("exitRequestId") UUID exitRequestId);
}