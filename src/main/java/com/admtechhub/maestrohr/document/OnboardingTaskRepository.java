package com.admtechhub.maestrohr.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OnboardingTaskRepository extends JpaRepository<OnboardingTask, UUID> {

    /** The ordered checklist for one employee. */
    List<OnboardingTask> findByEmployeeIdOrderByTaskOrderAsc(UUID employeeId);

    /** True once at least one task exists for the employee — guards idempotent seeding. */
    boolean existsByEmployeeId(UUID employeeId);

    /** Outstanding (unticked) tasks — used to decide whether the checklist still shows. */
    long countByEmployeeIdAndCompletedFalse(UUID employeeId);
}
