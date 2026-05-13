package com.admtechhub.maestrohr.payroll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface PayrollEntryRepository extends JpaRepository<PayrollEntry, UUID> {

    // Find all entries for a payroll run
    List<PayrollEntry> findByPayrollRunId(UUID payrollRunId);

    List<PayrollEntry> findByEmployeeId(UUID employeeId);

    java.util.Optional<PayrollEntry> findByPayrollRunIdAndEmployeeId(UUID payrollRunId, UUID employeeId);

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
}
