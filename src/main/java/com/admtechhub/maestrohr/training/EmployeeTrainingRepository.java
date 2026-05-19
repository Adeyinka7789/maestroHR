package com.admtechhub.maestrohr.training;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeTrainingRepository extends JpaRepository<EmployeeTraining, UUID> {

    @Query("SELECT et FROM EmployeeTraining et WHERE et.tenant.id = :tenantId ORDER BY et.createdAt DESC")
    Page<EmployeeTraining> findByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT et FROM EmployeeTraining et WHERE et.employee.id = :employeeId")
    List<EmployeeTraining> findByEmployeeId(@Param("employeeId") UUID employeeId);

    @Query("SELECT et FROM EmployeeTraining et WHERE et.training.id = :trainingId")
    List<EmployeeTraining> findByTrainingId(@Param("trainingId") UUID trainingId);
}