package com.admtechhub.maestrohr.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendancePolicyRepository extends JpaRepository<AttendancePolicy, UUID> {

    /** All non-deleted policies for the current tenant (RLS-scoped), oldest first. */
    List<AttendancePolicy> findAllByOrderByCreatedAtAsc();

    /** Fallback: first active policy when the employee's pay grade has no specific link. */
    Optional<AttendancePolicy> findFirstByIsActiveTrueOrderByCreatedAtAsc();
}
