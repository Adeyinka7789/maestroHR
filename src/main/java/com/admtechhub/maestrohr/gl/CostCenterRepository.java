package com.admtechhub.maestrohr.gl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CostCenterRepository extends JpaRepository<CostCenter, UUID> {

    List<CostCenter> findAllByOrderByNameAsc();

    List<CostCenter> findByActiveTrueOrderByNameAsc();

    Optional<CostCenter> findByCode(String code);

    boolean existsByCode(String code);
}
