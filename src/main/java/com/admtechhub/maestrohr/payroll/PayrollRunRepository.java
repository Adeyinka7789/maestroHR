package com.admtechhub.maestrohr.payroll;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    // Find by month and year
    Optional<PayrollRun> findByPayrollMonthAndPayrollYear(Integer month, Integer year);
    Optional<PayrollRun> findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(UUID tenantId, Integer month, Integer year);

    // Find by status
    List<PayrollRun> findByStatus(PayrollStatus status);
    List<PayrollRun> findByTenant_IdAndStatus(UUID tenantId, PayrollStatus status);

    Page<PayrollRun> findByStatus(PayrollStatus status, Pageable pageable);

    // Find pending approvals
    @Query("SELECT p FROM PayrollRun p WHERE p.status = 'PENDING_APPROVAL' ORDER BY p.createdAt DESC")
    List<PayrollRun> findPendingApprovals();

    // Check if payroll exists for month/year
    boolean existsByPayrollMonthAndPayrollYear(Integer month, Integer year);
    boolean existsByTenant_IdAndPayrollMonthAndPayrollYear(UUID tenantId, Integer month, Integer year);

    // Get payroll runs for a specific period
    @Query("SELECT p FROM PayrollRun p WHERE p.payrollYear = :year ORDER BY p.payrollMonth DESC")
    List<PayrollRun> findByYear(@Param("year") Integer year);

    // Get latest payroll run
    Optional<PayrollRun> findTopByOrderByCreatedAtDesc();
    Optional<PayrollRun> findTopByTenant_IdOrderByCreatedAtDesc(UUID tenantId);

    List<PayrollRun> findAllByTenant_IdOrderByCreatedAtDesc(UUID tenantId);
}
