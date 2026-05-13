package com.admtechhub.maestrohr.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {
    List<LeaveType> findByOrderByNameAsc();
    Optional<LeaveType> findByCode(String code);
    boolean existsByCode(String code);
}