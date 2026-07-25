package com.admtechhub.maestrohr.recruitment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobApplicationResumeRepository extends JpaRepository<JobApplicationResume, UUID> {

    Optional<JobApplicationResume> findByApplicationId(UUID applicationId);

    boolean existsByApplicationId(UUID applicationId);
}
