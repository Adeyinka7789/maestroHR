package com.admtechhub.maestrohr.adjustment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdjustmentTypeRepository extends JpaRepository<AdjustmentType, UUID> {

    List<AdjustmentType> findAllByOrderByDirectionAscNameAsc();

    List<AdjustmentType> findByActiveTrueOrderByDirectionAscNameAsc();

    Optional<AdjustmentType> findByCode(String code);

    /** Tenant-scoped count (RLS) used to decide whether default types need seeding. */
    long count();
}
