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
public interface JobApplicationRepository extends JpaRepository<JobApplication, UUID> {

    @Query("SELECT a FROM JobApplication a WHERE a.tenant.id = :tenantId ORDER BY a.createdAt DESC")
    Page<JobApplication> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT a FROM JobApplication a WHERE a.jobPosting.id = :jobPostingId ORDER BY a.createdAt DESC")
    List<JobApplication> findByJobPostingId(@Param("jobPostingId") UUID jobPostingId);

    @Query("SELECT COUNT(a) FROM JobApplication a WHERE a.jobPosting.id = :jobPostingId")
    long countByJobPostingId(@Param("jobPostingId") UUID jobPostingId);

    @Query("SELECT a FROM JobApplication a WHERE a.tenant.id = :tenantId AND a.status = :status")
    List<JobApplication> findByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") JobApplication.ApplicationStatus status);
}