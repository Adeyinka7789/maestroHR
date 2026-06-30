package com.admtechhub.maestrohr.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoanPolicyRepository extends JpaRepository<LoanPolicy, UUID> {

    /** All non-deleted policies for the current tenant (RLS-scoped), oldest first. */
    List<LoanPolicy> findAllByOrderByCreatedAtAsc();

    /** Fallback: first active policy when the employee's pay grade has no specific link. */
    Optional<LoanPolicy> findFirstByIsActiveTrueOrderByCreatedAtAsc();
}
