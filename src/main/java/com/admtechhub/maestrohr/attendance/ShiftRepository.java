package com.admtechhub.maestrohr.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    /** All non-deleted shifts for the current tenant (RLS-scoped), name-sorted. */
    List<Shift> findAllByOrderByNameAsc();

    /** Fallback: the tenant's default shift when an employee has no shift assigned. */
    Optional<Shift> findFirstByIsDefaultTrue();
}
