package com.admtechhub.maestrohr.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DiscountRepository extends JpaRepository<Discount, UUID> {

    /** Newest-first, for the admin management list. */
    List<Discount> findAllByOrderByCreatedAtDesc();

    /**
     * Every active, in-window discount that could apply to a purchase of {@code planName} /
     * {@code period} by {@code tenantId}. A dimension matches when the discount either targets
     * that exact value or leaves it {@code null} ("applies to all"). {@code DiscountService}
     * then picks whichever match yields the lowest net price.
     */
    @Query("""
            SELECT d FROM Discount d
             WHERE d.isActive = true
               AND (d.tenantId  IS NULL OR d.tenantId  = :tenantId)
               AND (d.planName  IS NULL OR d.planName  = :planName)
               AND (d.period    IS NULL OR d.period    = :period)
               AND (d.startsAt  IS NULL OR d.startsAt <= :now)
               AND (d.endsAt    IS NULL OR d.endsAt   >= :now)
            """)
    List<Discount> findApplicable(@Param("tenantId") UUID tenantId,
                                  @Param("planName") String planName,
                                  @Param("period") String period,
                                  @Param("now") OffsetDateTime now);
}
