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

    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.payrollRun.id = :payrollRunId AND pe.tenant.id = :tenantId")
    List<PayrollEntry> findByPayrollRunId(@Param("payrollRunId") UUID payrollRunId, @Param("tenantId") UUID tenantId);

    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.employee.id = :employeeId AND pe.tenant.id = :tenantId")
    List<PayrollEntry> findByEmployeeId(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(pe) > 0 FROM PayrollEntry pe WHERE pe.employee.id = :employeeId AND pe.tenant.id = :tenantId")
    boolean existsByEmployeeId(@Param("employeeId") UUID employeeId, @Param("tenantId") UUID tenantId);

    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.employee.id = :employeeId ORDER BY pe.payrollRun.createdAt DESC")
    List<PayrollEntry> findTop3ByEmployeeIdOrderByPayrollRunCreatedAtDesc(@Param("employeeId") UUID employeeId, Pageable pageable);

    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.employee.id = :employeeId ORDER BY pe.payrollRun.createdAt DESC")
    List<PayrollEntry> findByEmployeeIdOrderByPayrollRunCreatedAtDesc(@Param("employeeId") UUID employeeId);

    List<PayrollEntry> findByTransferStatus(TransferStatus status);

    @Query("SELECT e FROM PayrollEntry e WHERE e.payrollRun.id = :payrollRunId AND e.transferStatus = 'FAILED'")
    List<PayrollEntry> findFailedTransfers(@Param("payrollRunId") UUID payrollRunId);

    @Modifying
    @Transactional
    @Query("UPDATE PayrollEntry e SET e.transferStatus = :status, e.transferReference = :reference WHERE e.id = :entryId")
    void updateTransferStatus(@Param("entryId") UUID entryId,
                              @Param("status") TransferStatus status,
                              @Param("reference") String reference);

    @Modifying
    @Transactional
    @Query("UPDATE PayrollEntry e SET e.payslipGenerated = true WHERE e.id = :entryId")
    void markPayslipGenerated(@Param("entryId") UUID entryId);

    @Query("SELECT COUNT(e) FROM PayrollEntry e WHERE e.payrollRun.id = :payrollRunId AND e.transferStatus = :status")
    long countByTransferStatus(@Param("payrollRunId") UUID payrollRunId, @Param("status") TransferStatus status);

    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.payrollRun.id = :payrollRunId AND pe.employee.id = :employeeId")
    Optional<PayrollEntry> findByPayrollRunIdAndEmployeeId(@Param("payrollRunId") UUID payrollRunId, @Param("employeeId") UUID employeeId);

    @Query("SELECT pe FROM PayrollEntry pe WHERE pe.transferReference = :ref")
    Optional<PayrollEntry> findByTransferReference(@Param("ref") String ref);

    @Query("SELECT pe FROM PayrollEntry pe JOIN FETCH pe.employee JOIN FETCH pe.payrollRun WHERE pe.payrollRun.id = :runId")
    List<PayrollEntry> findByPayrollRunIdWithEntities(@Param("runId") UUID runId);

    @Query("SELECT e.payrollRun.id, COUNT(e) FROM PayrollEntry e " +
            "WHERE e.payrollRun.tenant.id = :tenantId GROUP BY e.payrollRun.id")
    List<Object[]> countEntriesByRunForTenant(@Param("tenantId") UUID tenantId);

    /**
     * Eagerly fetches individual lines mapped to an active tenant context scope.
     * Prevents lazy loading traps when compiling isolated itemized accounting reports.
     */
    @Query("SELECT pe FROM PayrollEntry pe " +
            "JOIN FETCH pe.employee emp " +
            "JOIN FETCH pe.payrollRun pr " +
            "JOIN FETCH pr.tenant t " +
            "WHERE pr.id = :payrollRunId AND t.id = :tenantId")
    List<PayrollEntry> findEntriesWithDetailsByRunAndTenant(@Param("payrollRunId") UUID payrollRunId, @Param("tenantId") UUID tenantId);
}