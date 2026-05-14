package com.admtechhub.maestrohr.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PricingConfigRepository extends JpaRepository<PricingConfig, UUID> {

    Optional<PricingConfig> findByPlanNameAndPeriod(String planName, String period);

    List<PricingConfig> findByPlanName(String planName);

    List<PricingConfig> findByIsActiveTrue();

    @Modifying
    @Transactional
    @Query("UPDATE PricingConfig p SET p.priceKobo = :price WHERE p.planName = :planName AND p.period = :period")
    void updatePrice(@Param("planName") String planName,
                     @Param("period") String period,
                     @Param("price") Long price);
}