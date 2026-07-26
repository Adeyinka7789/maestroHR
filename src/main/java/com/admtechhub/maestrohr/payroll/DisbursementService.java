package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.disbursement.SalaryPayment;
import com.admtechhub.maestrohr.disbursement.provider.CSVDisbursementProvider;
import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackUnknownStateException;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackException;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final DisbursementTransactionPhases transactionPhases;
    private final JobSweepQueries jobSweepQueries;

    /**
     * Orchestrates bulk salary disbursement. Deliberately NOT {@code @Transactional}: no DB
     * transaction or connection is held across the Paystack HTTP call. Each phase below is a
     * call into {@link DisbursementTransactionPhases} — a separate Spring bean — so the proxy
     * genuinely applies each phase's own {@code @Transactional} boundary, unlike the previous
     * same-bean self-invocation which silently ran everything (including the HTTP call) inside
     * one open transaction.
     *   Phase 1 (own transaction, commits before any HTTP call): mark the run DISBURSING.
     *   Phase 2 (no transaction): the Paystack HTTP call itself.
     *   Phase 3 (new transaction): record the outcome - success, FAILED, or DISBURSING_UNKNOWN.
     */
    public void disburseSalaries(UUID payrollRunId) {
        UUID tenantId = currentTenantId();

        // Phase 1 - commits before any HTTP call.
        DisbursementTransactionPhases.DisbursementAttempt attempt =
                transactionPhases.markRunDisbursing(payrollRunId, tenantId);

        // Phase 2 - no open transaction/connection while this runs.
        try {
            log.info("Executing bulk transfer for batch: {} with {} transfers",
                    attempt.batchReference(), attempt.transfers().size());

            PaystackResponse response = paystackClient.initiateBulkTransfer(attempt.transfers());

            // Phase 3 (success)
            transactionPhases.recordSuccessOutcome(payrollRunId, tenantId, response);
            log.info("Bulk transfer successful for batch: {}", attempt.batchReference());

        } catch (PaystackUnknownStateException e) {
            log.error("CRITICAL: Network timeout during bulk transfer. Batch: {}. State preserved in DB.",
                    attempt.batchReference(), e);
            String apiStatus = attemptImmediateVerification(attempt.batchReference());
            // Phase 3 (unknown) - genuinely REQUIRES_NEW now that it's a real proxied call.
            transactionPhases.recordReconciliationResult(payrollRunId, tenantId, apiStatus);

        } catch (PaystackException e) {
            log.error("Paystack API rejected transfer for batch: {}. Rolling back to FAILED.",
                    attempt.batchReference(), e);
            // Phase 3 (failure)
            transactionPhases.rollbackRunToFailed(payrollRunId, tenantId);
        }
    }

    /** Outcome of a retry sweep, surfaced to the operator as a banner. */
    public record RetryResult(int count, String message) {}

    /**
     * Self-service retry of a run's FAILED / REVERSED transfers. Same phase discipline as
     * {@link #disburseSalaries} but scoped to the failed subset: Phase 1 stamps fresh unique
     * references and rebuilds the transfer list (committed before the HTTP call); Phase 2 is the
     * Paystack bulk call; Phase 3 records the outcome — DISBURSING on a submitted retry (the
     * transfer webhook reconciles by reference), FAILED when Paystack rejected. Not
     * {@code @Transactional}: no DB connection is held across the HTTP call.
     */
    public RetryResult retryFailedTransfers(UUID payrollRunId) {
        UUID tenantId = currentTenantId();

        DisbursementTransactionPhases.RetryAttempt attempt =
                transactionPhases.markFailedEntriesRetrying(payrollRunId, tenantId);
        if (attempt.transfers().isEmpty()) {
            return new RetryResult(0,
                    "No eligible failed or reversed transfers to retry — each needs a linked bank recipient.");
        }

        try {
            paystackClient.initiateBulkTransfer(attempt.transfers());
            transactionPhases.finalizeRetry(payrollRunId, tenantId, attempt.entryIds(), TransferStatus.DISBURSING);
            log.info("Retry initiated for {} transfer(s) on run {}", attempt.entryIds().size(), payrollRunId);
            return new RetryResult(attempt.entryIds().size(),
                    attempt.entryIds().size() + " transfer(s) re-initiated — statuses update as Paystack confirms.");

        } catch (PaystackUnknownStateException e) {
            // Almost certainly submitted; leave DISBURSING for the transfer.* webhook to reconcile.
            transactionPhases.finalizeRetry(payrollRunId, tenantId, attempt.entryIds(), TransferStatus.DISBURSING);
            log.error("Retry unknown state for run {}; left DISBURSING for webhook reconciliation", payrollRunId, e);
            return new RetryResult(attempt.entryIds().size(),
                    "Retry submitted; confirmation is pending and will update automatically.");

        } catch (PaystackException e) {
            transactionPhases.finalizeRetry(payrollRunId, tenantId, attempt.entryIds(), TransferStatus.FAILED);
            log.error("Retry rejected by Paystack for run {}", payrollRunId, e);
            throw new IllegalStateException("Paystack rejected the retry: " + e.getMessage());
        }
    }

    /**
     * Best-effort immediate verification call, made outside any transaction. Returns the raw
     * Paystack status string, or null if the call itself failed or returned no data - either of
     * which leaves the run DISBURSING_UNKNOWN for the background sweep to retry.
     */
    private String attemptImmediateVerification(String batchReference) {
        try {
            log.info("Attempting immediate reconciliation for batch: {}", batchReference);
            PaystackResponse verification = paystackClient.verifyBulkTransfer(batchReference);
            String apiStatus = verification.getData() != null ? verification.getData().getStatus() : null;
            log.info("Reconciliation result for batch {}: {}", batchReference, apiStatus);
            return apiStatus;
        } catch (Exception e) {
            log.error("Immediate reconciliation call failed for batch: {}. Marking DISBURSING_UNKNOWN.",
                    batchReference, e);
            return null;
        }
    }

    /**
     * Scheduled job to reconcile all DISBURSING_UNKNOWN payroll runs, across every tenant.
     * Runs every 2 minutes to check on uncertain transfers.
     *
     * <p>Fixed to use the same privileged-scan + per-tenant-bind pattern as
     * {@code PayrollReminderJob}: the scheduler has no tenant session, so a plain
     * {@code findByStatus(DISBURSING_UNKNOWN)} against the RLS-enforced datasource silently
     * returned zero rows for every tenant (dead code, not just untested). {@link JobSweepQueries}
     * runs through the privileged datasource to find candidates across all tenants; each run is
     * then processed with {@link TenantContext} bound to its own tenant, cleared in a
     * {@code finally}. Not {@code @Transactional} at the method level - each run's Paystack
     * verification call happens with no open transaction, and its outcome is persisted in its
     * own {@code REQUIRES_NEW} transaction via {@link DisbursementTransactionPhases}.
     */
    @Scheduled(fixedDelay = 120000) // Every 2 minutes
    public void reconcileUnknownDisbursements() {
        List<JobSweepQueries.DisbursingUnknownRow> candidates = jobSweepQueries.findDisbursingUnknownRuns();

        if (candidates.isEmpty()) {
            return;
        }

        log.info("Found {} payroll run(s) in DISBURSING_UNKNOWN state. Starting reconciliation.",
                candidates.size());

        int successCount = 0;
        int failedCount = 0;
        int stillPendingCount = 0;

        for (JobSweepQueries.DisbursingUnknownRow row : candidates) {
            try {
                TenantContext.setCurrentTenant(row.tenantId().toString());

                if (row.batchReference() == null) {
                    log.error("Payroll run {} is DISBURSING_UNKNOWN but has no batch reference. " +
                            "Marking FAILED for manual review.", row.runId());
                    transactionPhases.rollbackRunToFailed(row.runId(), row.tenantId());
                    failedCount++;
                    continue;
                }

                log.debug("Background reconciling payroll: {} with batch: {}", row.runId(), row.batchReference());

                String apiStatus;
                try {
                    PaystackResponse verification = paystackClient.verifyBulkTransfer(row.batchReference());
                    apiStatus = verification.getData() != null ? verification.getData().getStatus() : null;
                } catch (Exception e) {
                    log.error("Background reconciliation error for payroll: {}. Will retry.", row.runId(), e);
                    stillPendingCount++;
                    continue;
                }

                DisbursementTransactionPhases.ReconciliationOutcome outcome =
                        transactionPhases.recordReconciliationResult(row.runId(), row.tenantId(), apiStatus);

                switch (outcome) {
                    case CONFIRMED_SUCCESS -> {
                        successCount++;
                        log.info("Background reconciliation: Payroll {} confirmed SUCCESS", row.runId());
                    }
                    case CONFIRMED_FAILED -> {
                        failedCount++;
                        log.warn("Background reconciliation: Payroll {} confirmed FAILED", row.runId());
                    }
                    case STILL_UNKNOWN -> {
                        stillPendingCount++;
                        log.debug("Background reconciliation: Payroll {} still {} - will retry",
                                row.runId(), apiStatus);
                    }
                }

            } catch (Exception e) {
                log.error("Background reconciliation error for payroll: {}. Will retry.", row.runId(), e);
                stillPendingCount++;
            } finally {
                TenantContext.clear();
            }
        }

        log.info("Background reconciliation complete. Success: {}, Failed: {}, Still Pending: {}",
                successCount, failedCount, stillPendingCount);
    }

    /**
     * Disburse via CSV export for manual bank processing. Self-contained (no internal calls to
     * other transactional methods of this bean), so it never had the self-invocation bug and is
     * unchanged by this fix.
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
                        .reference(DisbursementTransactionPhases.generateReference(entry))
                        .narration("Salary payment for " + payrollRun.getPeriod())
                        .paystackRecipientCode(entry.getEmployee().getPaystackRecipientCode())
                        .build())
                .toList();

        byte[] csvBytes = csvDisbursementProvider.generateCSVFile(payments);

        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.PAID);
            entry.setTransferReference(DisbursementTransactionPhases.generateReference(entry));
        }
        payrollEntryRepository.saveAll(entries);

        payrollRun.setStatus(PayrollStatus.COMPLETED);
        payrollRunRepository.save(payrollRun);

        log.info("CSV disbursement complete for payroll {}: {} entries marked COMPLETED",
                payrollRunId, entries.size());
        return csvBytes;
    }

    private UUID currentTenantId() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalStateException("No tenant context available for disbursement operation");
        }
        return UUID.fromString(tenantIdStr);
    }
}
