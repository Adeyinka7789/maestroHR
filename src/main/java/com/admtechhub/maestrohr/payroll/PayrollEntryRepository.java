package com.admtechhub.maestrohr.payroll;

import org.springframework.data.domain.Pageable;
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
public interface PayrollEntryRepository extends JpaRepository<PayrollEntry, UUID> {

    // Find all entries for a payroll run
    List<PayrollEntry> findByPayrollRunId(UUID payrollRunId);

    // Find entries by employee ID (for employee dashboard)
    List<PayrollEntry> findByEmployeeId(UUID employeeId);

    // Find top N entries by employee ID ordered by payroll run creation date desc
    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.employee.id = :employeeId ORDER BY pe.payrollRun.createdAt DESC")
    List<PayrollEntry> findTop3ByEmployeeIdOrderByPayrollRunCreatedAtDesc(@Param("employeeId") UUID employeeId, Pageable pageable);

    // Find all entries by employee ID ordered by payroll run creation date desc
    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.employee.id = :employeeId ORDER BY pe.payrollRun.createdAt DESC")
    List<PayrollEntry> findByEmployeeIdOrderByPayrollRunCreatedAtDesc(@Param("employeeId") UUID employeeId);

    // Find entries by transfer status
    List<PayrollEntry> findByTransferStatus(TransferStatus status);

    // Find failed transfers for a payroll run
    @Query("SELECT e FROM PayrollEntry e WHERE e.payrollRun.id = :payrollRunId AND e.transferStatus = 'FAILED'")
    List<PayrollEntry> findFailedTransfers(@Param("payrollRunId") UUID payrollRunId);

    // Update transfer status for an entry
    @Modifying
    @Transactional
    @Query("UPDATE PayrollEntry e SET e.transferStatus = :status, e.transferReference = :reference WHERE e.id = :entryId")
    void updateTransferStatus(@Param("entryId") UUID entryId,
                              @Param("status") TransferStatus status,
                              @Param("reference") String reference);

    // Mark payslip as generated
    @Modifying
    @Transactional
    @Query("UPDATE PayrollEntry e SET e.payslipGenerated = true WHERE e.id = :entryId")
    void markPayslipGenerated(@Param("entryId") UUID entryId);

    // Count entries by transfer status for a payroll run
    @Query("SELECT COUNT(e) FROM PayrollEntry e WHERE e.payrollRun.id = :payrollRunId AND e.transferStatus = :status")
    long countByTransferStatus(@Param("payrollRunId") UUID payrollRunId, @Param("status") TransferStatus status);

    // Find a specific payroll entry by payroll run ID and employee ID
    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.payrollRun.id = :payrollRunId AND pe.employee.id = :employeeId")
    Optional<PayrollEntry> findByPayrollRunIdAndEmployeeId(@Param("payrollRunId") UUID payrollRunId, @Param("employeeId") UUID employeeId);
}