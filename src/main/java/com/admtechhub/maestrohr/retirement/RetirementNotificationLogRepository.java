package com.admtechhub.maestrohr.retirement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RetirementNotificationLogRepository extends JpaRepository<RetirementNotificationLog, UUID> {

    /** Whether HR has already been notified for this (employee, threshold) pair — makes the job idempotent. */
    boolean existsByEmployeeIdAndThresholdDays(UUID employeeId, Integer thresholdDays);
}
