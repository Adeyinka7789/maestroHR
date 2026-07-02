package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.disbursement.SalaryPayment;
import com.admtechhub.maestrohr.disbursement.provider.CSVDisbursementProvider;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackUnknownStateException;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackException;
import com.admtechhub.maestrohr.paystack.dto.PaystackRequest;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisbursementService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final PaystackClient paystackClient;
    private final CSVDisbursementProvider csvDisbursementProvider;

    /**
     * Initiate bulk salary disbursement.
     * Phase 1 (@Transactional): Save references to DB, then exit transaction.
     * Phase 2 (no transaction): Call Paystack API.
     * Recovery: Handles unknown state with immediate and scheduled reconciliation.
     */
    @Transactional
    public PayrollRun disburseSalaries(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        // Only allow disbursement from APPROVED or previously FAILED states
        if (payrollRun.getStatus() != PayrollStatus.APPROVED &&
                payrollRun.getStatus() != PayrollStatus.FAILED) {
            throw new IllegalStateException(
                    "Payroll must be APPROVED or FAILED before disbursement. Current status: " +
                            payrollRun.getStatus());
        }

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        List<PayrollEntry> pendingEntries = entries.stream()
                .filter(e -> e.getTransferStatus() == TransferStatus.PENDING)
                .toList();

        if (pendingEntries.isEmpty()) {
            throw new IllegalStateException("No pending entries found for disbursement");
        }

        // Generate and persist batch reference BEFORE Paystack call
        String batchReference = "BATCH-" + payrollRun.getId().toString().substring(0, 8) +
                "-" + System.currentTimeMillis() / 1000;
        payrollRun.setBatchReference(batchReference);
        payrollRun.setStatus(PayrollStatus.DISBURSING);

        List<PaystackRequest.Transfer> transfers = new ArrayList<>();
        for (PayrollEntry entry : pendingEntries) {
            String recipientCode = entry.getEmployee().getPaystackRecipientCode();
            if (recipientCode == null) {
                log.warn("Employee {} has no recipient code, skipping",
                        entry.getEmployee().getEmployeeNumber());
                continue;
            }

            String entryReference = generateReference(entry);
            entry.setTransferReference(entryReference);
            entry.setTransferStatus(TransferStatus.PENDING);
            payrollEntryRepository.save(entry);

            transfers.add(PaystackRequest.Transfer.builder()
                    .amount(entry.getNetSalary().intValue())
                    .recipient(recipientCode)
                    .reference(entryReference)
                    .reason("Salary payment for " + payrollRun.getPeriod())
                    .build());
        }

        if (transfers.isEmpty()) {
            throw new IllegalStateException(
                    "No valid transfers found - all entries missing recipient codes");
        }

        // Save payroll run with references BEFORE exiting transaction
        payrollRunRepository.save(payrollRun);

        // Transaction commits here. References are safely in DB.
        // Now call Paystack OUTSIDE the transaction
        return executeOutboundTransfer(payrollRunId, batchReference, transfers);
    }

    /**
     * Phase 2: Execute Paystack API call OUTSIDE of any transaction.
     * If this fails, our saved references remain intact in the database.
     */
    public PayrollRun executeOutboundTransfer(UUID payrollRunId, String batchReference,
                                              List<PaystackRequest.Transfer> transfers) {
        try {
            log.info("Executing bulk transfer for batch: {} with {} transfers",
                    batchReference, transfers.size());

            PaystackResponse response = paystackClient.initiateBulkTransfer(transfers);

            // Success - update with transfer codes
            updateRunWithTransferCodes(payrollRunId, response);

            log.info("Bulk transfer successful for batch: {}", batchReference);
            return payrollRunRepository.findById(payrollRunId).orElseThrow();

        } catch (PaystackUnknownStateException e) {
            log.error("CRITICAL: Network timeout during bulk transfer. Batch: {}. State preserved in DB.",
                    batchReference, e);
            return reconcileUnknownStateImmediately(payrollRunId, batchReference);

        } catch (PaystackException e) {
            log.error("Paystack API rejected transfer for batch: {}. Rolling back to FAILED.",
                    batchReference, e);
            return rollbackRunToFailed(payrollRunId);
        }
    }

    /**
     * Update entries with Paystack transfer codes after successful initiation.
     * Called from non-transactional context, so it creates its own transaction.
     */
    @Transactional
    protected void updateRunWithTransferCodes(UUID payrollRunId, PaystackResponse response) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId).orElseThrow();

        if (response.getData() != null && response.getData().getTransferCodes() != null) {
            List<String> transferCodes = response.getData().getTransferCodes();
            List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());

            int updatedCount = 0;
            for (PayrollEntry entry : entries) {
                if (entry.getTransferStatus() == TransferStatus.PENDING &&
                        updatedCount < transferCodes.size()) {
                    entry.setPaystackTransferCode(transferCodes.get(updatedCount));
                    entry.setTransferStatus(TransferStatus.DISBURSING);
                    payrollEntryRepository.save(entry);
                    updatedCount++;
                }
            }
            log.info("Updated {} entries with Paystack transfer codes", updatedCount);
        }

        // Status stays as DISBURSING (already set in Phase 1)
        payrollRunRepository.save(payrollRun);
    }

    /**
     * Immediate reconciliation attempt when PaystackUnknownStateException occurs.
     * Uses REQUIRES_NEW to ensure this persists regardless of any parent transaction state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PayrollRun reconcileUnknownStateImmediately(UUID payrollRunId, String batchReference) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payroll run not found for reconciliation: " + payrollRunId));

        try {
            log.info("Attempting immediate reconciliation for batch: {}", batchReference);
            PaystackResponse verification = paystackClient.verifyBulkTransfer(batchReference);

            if (verification.getData() != null) {
                String apiStatus = verification.getData().getStatus();
                log.info("Reconciliation result for batch {}: {}", batchReference, apiStatus);

                if ("success".equalsIgnoreCase(apiStatus) ||
                        "completed".equalsIgnoreCase(apiStatus)) {
                    // Transfer confirmed successful
                    payrollRun.setStatus(PayrollStatus.DISBURSING);
                    updateEntriesToDisbursing(payrollRunId);
                    log.info("Batch {} confirmed SUCCESS. Payroll staying in DISBURSING.",
                            batchReference);

                } else if ("failed".equalsIgnoreCase(apiStatus) ||
                        "reversed".equalsIgnoreCase(apiStatus)) {
                    // Transfer confirmed failed - safe to retry
                    payrollRun.setStatus(PayrollStatus.FAILED);
                    resetEntriesToPending(payrollRunId);
                    log.warn("Batch {} confirmed FAILED. Payroll reverted to FAILED for retry.",
                            batchReference);

                } else {
                    // Still processing - set to unknown for background check
                    payrollRun.setStatus(PayrollStatus.DISBURSING_UNKNOWN);
                    log.warn("Batch {} still processing (status: {}). Marked DISBURSING_UNKNOWN.",
                            batchReference, apiStatus);
                }
            } else {
                payrollRun.setStatus(PayrollStatus.DISBURSING_UNKNOWN);
                log.warn("Empty verification response for batch {}. Marked DISBURSING_UNKNOWN.",
                        batchReference);
            }

        } catch (Exception e) {
            log.error("Immediate reconciliation failed for batch: {}. Marking DISBURSING_UNKNOWN.",
                    batchReference, e);
            payrollRun.setStatus(PayrollStatus.DISBURSING_UNKNOWN);
        }

        return payrollRunRepository.save(payrollRun);
    }

    /**
     * Scheduled job to reconcile all DISBURSING_UNKNOWN payroll runs.
     * Runs every 2 minutes to check on uncertain transfers.
     */
    @Scheduled(fixedDelay = 120000) // Every 2 minutes
    @Transactional
    public void reconcileUnknownDisbursements() {
        List<PayrollRun> unknownRuns = payrollRunRepository.findByStatus(
                PayrollStatus.DISBURSING_UNKNOWN);

        if (unknownRuns.isEmpty()) {
            return;
        }

        log.info("Found {} payroll runs in DISBURSING_UNKNOWN state. Starting reconciliation.",
                unknownRuns.size());

        int successCount = 0;
        int failedCount = 0;
        int stillPendingCount = 0;

        for (PayrollRun run : unknownRuns) {
            try {
                if (run.getBatchReference() == null) {
                    log.error("Payroll run {} is DISBURSING_UNKNOWN but has no batch reference. " +
                            "Marking FAILED for manual review.", run.getId());
                    run.setStatus(PayrollStatus.FAILED);
                    payrollRunRepository.save(run);
                    failedCount++;
                    continue;
                }

                log.debug("Background reconciling payroll: {} with batch: {}",
                        run.getId(), run.getBatchReference());

                PaystackResponse verification = paystackClient.verifyBulkTransfer(
                        run.getBatchReference());

                if (verification.getData() != null) {
                    String apiStatus = verification.getData().getStatus();

                    if ("success".equalsIgnoreCase(apiStatus) ||
                            "completed".equalsIgnoreCase(apiStatus)) {
                        run.setStatus(PayrollStatus.DISBURSING);
                        updateEntriesToDisbursing(run.getId());
                        payrollRunRepository.save(run);
                        successCount++;
                        log.info("Background reconciliation: Payroll {} confirmed SUCCESS", run.getId());

                    } else if ("failed".equalsIgnoreCase(apiStatus) ||
                            "reversed".equalsIgnoreCase(apiStatus)) {
                        run.setStatus(PayrollStatus.FAILED);
                        resetEntriesToPending(run.getId());
                        payrollRunRepository.save(run);
                        failedCount++;
                        log.warn("Background reconciliation: Payroll {} confirmed FAILED", run.getId());

                    } else {
                        // Still processing - leave as DISBURSING_UNKNOWN
                        stillPendingCount++;
                        log.debug("Background reconciliation: Payroll {} still {} - will retry",
                                run.getId(), apiStatus);
                    }
                }

            } catch (Exception e) {
                log.error("Background reconciliation error for payroll: {}. Will retry.",
                        run.getId(), e);
                stillPendingCount++;
            }
        }

        log.info("Background reconciliation complete. Success: {}, Failed: {}, Still Pending: {}",
                successCount, failedCount, stillPendingCount);
    }

    /**
     * Update entries to DISBURSING status when transfer confirmed.
     */
    private void updateEntriesToDisbursing(UUID payrollRunId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        for (PayrollEntry entry : entries) {
            if (entry.getTransferStatus() == TransferStatus.PENDING) {
                entry.setTransferStatus(TransferStatus.DISBURSING);
                payrollEntryRepository.save(entry);
            }
        }
    }

    /**
     * Reset entries to PENDING when transfer definitively failed.
     */
    private void resetEntriesToPending(UUID payrollRunId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.PENDING);
            entry.setTransferReference(null);
            entry.setPaystackTransferCode(null);
            payrollEntryRepository.save(entry);
        }
    }

    /**
     * Rollback payroll run to FAILED state, resetting entries for retry.
     */
    @Transactional
    public PayrollRun rollbackRunToFailed(UUID payrollRunId) {
        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payroll run not found: " + payrollRunId));

        run.setStatus(PayrollStatus.FAILED);
        resetEntriesToPending(payrollRunId);

        log.info("Payroll run {} rolled back to FAILED for retry", payrollRunId);
        return payrollRunRepository.save(run);
    }

    /**
     * Disburse via CSV export for manual bank processing.
     */
    @Transactional
    public byte[] disburseSalariesCsv(UUID payrollRunId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payroll run not found: " + payrollRunId));

        if (payrollRun.getStatus() != PayrollStatus.APPROVED) {
            throw new IllegalStateException(
                    "Payroll must be APPROVED before CSV disbursement. Current status: " +
                            payrollRun.getStatus());
        }

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, currentTenantId());
        if (entries.isEmpty()) {
            throw new IllegalStateException("No entries found for payroll run: " + payrollRunId);
        }

        List<SalaryPayment> payments = entries.stream()
                .map(entry -> SalaryPayment.builder()
                        .employeeId(entry.getEmployee().getId().toString())
                        .employeeNumber(entry.getEmployee().getEmployeeNumber())
                        .employeeName(entry.getEmployee().getFullName())
                        .accountNumber(entry.getEmployee().getBankAccountNumber())
                        .bankCode(entry.getEmployee().getBankName())
                        .bankName(entry.getEmployee().getBankName())
                        .accountName(entry.getEmployee().getBankAccountName())
                        .amountKobo(entry.getNetSalary())
                        .reference(generateReference(entry))
                        .narration("Salary payment for " + payrollRun.getPeriod())
                        .paystackRecipientCode(entry.getEmployee().getPaystackRecipientCode())
                        .build())
                .toList();

        byte[] csvBytes = csvDisbursementProvider.generateCSVFile(payments);

        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.PAID);
            entry.setTransferReference(generateReference(entry));
        }
        payrollEntryRepository.saveAll(entries);

        payrollRun.setStatus(PayrollStatus.COMPLETED);
        payrollRunRepository.save(payrollRun);

        log.info("CSV disbursement complete for payroll {}: {} entries marked COMPLETED",
                payrollRunId, entries.size());
        return csvBytes;
    }

    private String generateReference(PayrollEntry entry) {
        String tenantPrefix = entry.getPayrollRun().getTenant().getId().toString().substring(0, 8);
        return String.format("SAL-%s-%s-%s",
                tenantPrefix,
                entry.getPayrollRun().getPeriod(),
                entry.getEmployee().getEmployeeNumber());
    }

    private UUID currentTenantId() {
        return UUID.fromString(TenantContext.getCurrentTenant());
    }
}
