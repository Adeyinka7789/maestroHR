package com.admtechhub.maestrohr.exit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClearanceItemRepository extends JpaRepository<ClearanceItem, UUID> {

    @Query("SELECT c FROM ClearanceItem c WHERE c.tenant.id = :tenantId ORDER BY c.sortOrder ASC")
    List<ClearanceItem> findByTenantId(@Param("tenantId") UUID tenantId);
}