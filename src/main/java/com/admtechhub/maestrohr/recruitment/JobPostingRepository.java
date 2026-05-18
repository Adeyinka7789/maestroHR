package com.admtechhub.maestrohr.recruitment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    @Query("SELECT j FROM JobPosting j WHERE j.tenant.id = :tenantId ORDER BY j.createdAt DESC")
    Page<JobPosting> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT j FROM JobPosting j WHERE j.tenant.id = :tenantId AND j.status = 'PUBLISHED' ORDER BY j.postedDate DESC")
    List<JobPosting> findPublishedJobs(@Param("tenantId") UUID tenantId);

    @Query("SELECT j FROM JobPosting j WHERE j.tenant.id = :tenantId AND j.status IN ('DRAFT', 'PUBLISHED')")
    List<JobPosting> findActiveJobs(@Param("tenantId") UUID tenantId);
}