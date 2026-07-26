package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.paystack.dto.PaystackRequest;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transactional phases behind {@link DisbursementService}'s disbursement orchestration,
 * extracted into their own Spring bean so each {@code @Transactional} boundary is enforced by
 * the AOP proxy rather than silently bypassed by same-bean self-invocation — the bug this class
 * fixes. {@link DisbursementService} is the non-transactional orchestrator: it calls these
 * phase methods in order, through this bean's real proxy, with the Paystack HTTP call sitting
 * entirely outside any of them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisbursementTransactionPhases {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;

    /** What Phase 1 hands the orchestrator for the (transaction-free) Paystack call. */
    public record DisbursementAttempt(String batchReference, List<PaystackRequest.Transfer> transfers) {}

    /** Outcome of interpreting a Paystack verification response, shared by the immediate
     *  reconciliation path and the background sweep. */
    public enum ReconciliationOutcome { CONFIRMED_SUCCESS, CONFIRMED_FAILED, STILL_UNKNOWN }

    /**
     * Phase 1 — its own transaction, commits before any HTTP call. Validates the run, marks it
     * (and its pending entries) DISBURSING, and persists a batch reference — the "attempting
     * this disbursement" state — so that state is durable even if the Paystack call that
     * follows throws or the process crashes before Phase 3 runs.
     */
    @Transactional
    public DisbursementAttempt markRunDisbursing(UUID payrollRunId, UUID tenantId) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        if (payrollRun.getStatus() != PayrollStatus.APPROVED &&
                payrollRun.getStatus() != PayrollStatus.FAILED) {
            throw new IllegalStateException(
                    "Payroll must be APPROVED or FAILED before disbursement. Current status: " +
                            payrollRun.getStatus());
        }

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, tenantId);
        List<PayrollEntry> pendingEntries = entries.stream()
                .filter(e -> e.getTransferStatus() == TransferStatus.PENDING)
                .toList();

        if (pendingEntries.isEmpty()) {
            throw new IllegalStateException("No pending entries found for disbursement");
        }

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
                    // toIntExact fails loudly on overflow instead of silently truncating a
                    // net salary above Integer.MAX_VALUE kobo (~₦21.47M) into a wrong amount.
                    .amount(Math.toIntExact(entry.getNetSalary()))
                    .recipient(recipientCode)
                    .reference(entryReference)
                    .reason("Salary payment for " + payrollRun.getPeriod())
                    .build());
        }

        if (transfers.isEmpty()) {
            throw new IllegalStateException(
                    "No valid transfers found - all entries missing recipient codes");
        }

        payrollRunRepository.save(payrollRun);
        log.info("Phase 1 committed: run {} marked DISBURSING with batch {}", payrollRunId, batchReference);
        return new DisbursementAttempt(batchReference, transfers);
    }

    /**
     * Phase 3 (success) — its own transaction. Records the Paystack transfer codes returned
     * after a successful bulk-transfer call. The run itself stays DISBURSING (set in Phase 1);
     * only the entries move to DISBURSING with their transfer codes attached.
     */
    @Transactional
    public void recordSuccessOutcome(UUID payrollRunId, UUID tenantId, PaystackResponse response) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId).orElseThrow();

        if (response.getData() != null && response.getData().getTransferCodes() != null) {
            List<String> transferCodes = response.getData().getTransferCodes();
            List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, tenantId);

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

        payrollRunRepository.save(payrollRun);
    }

    /**
     * Phase 3 (Paystack flat-out rejected the bulk request, or — reused by the background
     * sweep — a DISBURSING_UNKNOWN run has no batch reference to verify at all) — its own
     * transaction. Rolls the run back to FAILED and resets its entries to PENDING for retry.
     */
    @Transactional
    public PayrollRun rollbackRunToFailed(UUID payrollRunId, UUID tenantId) {
        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalStateException("Payroll run not found: " + payrollRunId));

        run.setStatus(PayrollStatus.FAILED);
        resetEntriesToPending(payrollRunId, tenantId);

        log.info("Payroll run {} rolled back to FAILED for retry", payrollRunId);
        return payrollRunRepository.save(run);
    }

    /**
     * Phase 3 (unknown-state) — genuinely its own transaction ({@code REQUIRES_NEW}), reachable
     * only through this bean's Spring proxy so the propagation actually applies (the bug this
     * class fixes: this method used to be self-invoked from within {@code DisbursementService},
     * which silently bypassed the proxy and this annotation). Takes an already-fetched Paystack
     * verification status (the HTTP call itself always happens in the caller, outside any
     * transaction) and persists whatever it means. Used both right after a
     * {@code PaystackUnknownStateException} (single run, immediate) and by the background sweep
     * (one run at a time) — both callers interpret the same apiStatus vocabulary, so the
     * persistence logic is shared here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationOutcome recordReconciliationResult(UUID payrollRunId, UUID tenantId, String apiStatus) {
        PayrollRun payrollRun = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payroll run not found for reconciliation: " + payrollRunId));

        if (apiStatus != null && ("success".equalsIgnoreCase(apiStatus) || "completed".equalsIgnoreCase(apiStatus))) {
            payrollRun.setStatus(PayrollStatus.DISBURSING);
            updateEntriesToDisbursing(payrollRunId, tenantId);
            payrollRunRepository.save(payrollRun);
            log.info("Reconciliation confirmed SUCCESS for run {}", payrollRunId);
            return ReconciliationOutcome.CONFIRMED_SUCCESS;

        } else if (apiStatus != null && ("failed".equalsIgnoreCase(apiStatus) || "reversed".equalsIgnoreCase(apiStatus))) {
            payrollRun.setStatus(PayrollStatus.FAILED);
            resetEntriesToPending(payrollRunId, tenantId);
            payrollRunRepository.save(payrollRun);
            log.warn("Reconciliation confirmed FAILED for run {}", payrollRunId);
            return ReconciliationOutcome.CONFIRMED_FAILED;

        } else {
            // Ambiguous/absent response - leave (or re-mark) DISBURSING_UNKNOWN for the next sweep.
            payrollRun.setStatus(PayrollStatus.DISBURSING_UNKNOWN);
            payrollRunRepository.save(payrollRun);
            log.warn("Reconciliation still unresolved for run {} (apiStatus={})", payrollRunId, apiStatus);
            return ReconciliationOutcome.STILL_UNKNOWN;
        }
    }

    // ── Per-entry retry of FAILED / REVERSED transfers ──────────────────────────────

    /** What Phase 1 of a retry hands the orchestrator: the transfers plus the entries they cover. */
    public record RetryAttempt(List<PaystackRequest.Transfer> transfers, List<UUID> entryIds) {}

    /**
     * Retry Phase 1 (own transaction) — collect this run's FAILED / REVERSED entries that have a
     * linked bank recipient, stamp each with a FRESH unique transfer reference (the original one
     * was already consumed at Paystack, so it can't be reused), flip them to PENDING, and build the
     * transfer list. Entries without a recipient code are left untouched (nothing to send).
     */
    @Transactional
    public RetryAttempt markFailedEntriesRetrying(UUID payrollRunId, UUID tenantId) {
        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: " + payrollRunId));

        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, tenantId);
        List<PaystackRequest.Transfer> transfers = new ArrayList<>();
        List<UUID> entryIds = new ArrayList<>();
        long suffix = System.currentTimeMillis() / 1000;

        for (PayrollEntry entry : entries) {
            if (entry.getTransferStatus() != TransferStatus.FAILED
                    && entry.getTransferStatus() != TransferStatus.REVERSED) {
                continue;
            }
            String recipientCode = entry.getEmployee().getPaystackRecipientCode();
            if (recipientCode == null) {
                continue; // no payment method — leave it flagged for HR to fix
            }
            String reference = generateReference(entry) + "-R" + suffix;
            entry.setTransferReference(reference);
            entry.setPaystackTransferCode(null);
            entry.setTransferStatus(TransferStatus.PENDING);
            payrollEntryRepository.save(entry);
            entryIds.add(entry.getId());

            transfers.add(PaystackRequest.Transfer.builder()
                    .amount(Math.toIntExact(entry.getNetSalary()))
                    .recipient(recipientCode)
                    .reference(reference)
                    .reason("Salary retry for " + run.getPeriod())
                    .build());
        }
        return new RetryAttempt(transfers, entryIds);
    }

    /**
     * Retry Phase 3 (own transaction) — move the just-retried entries to {@code finalStatus}
     * ({@code DISBURSING} on a submitted retry, so the transfer webhook reconciles them by
     * reference; {@code FAILED} when Paystack outright rejected). Only touches entries still
     * PENDING, so a webhook that already resolved one is not clobbered.
     */
    @Transactional
    public void finalizeRetry(UUID payrollRunId, UUID tenantId, List<UUID> entryIds, TransferStatus finalStatus) {
        java.util.Set<UUID> ids = new java.util.HashSet<>(entryIds);
        for (PayrollEntry entry : payrollEntryRepository.findByPayrollRunId(payrollRunId, tenantId)) {
            if (!ids.contains(entry.getId()) || entry.getTransferStatus() != TransferStatus.PENDING) {
                continue;
            }
            if (finalStatus == TransferStatus.FAILED) {
                entry.setTransferReference(null);
                entry.setPaystackTransferCode(null);
            }
            entry.setTransferStatus(finalStatus);
            payrollEntryRepository.save(entry);
        }
    }

    /** Update entries to DISBURSING status when transfer confirmed. */
    private void updateEntriesToDisbursing(UUID payrollRunId, UUID tenantId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, tenantId);
        for (PayrollEntry entry : entries) {
            if (entry.getTransferStatus() == TransferStatus.PENDING) {
                entry.setTransferStatus(TransferStatus.DISBURSING);
                payrollEntryRepository.save(entry);
            }
        }
    }

    /** Reset entries to PENDING when transfer definitively failed. */
    private void resetEntriesToPending(UUID payrollRunId, UUID tenantId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId, tenantId);
        for (PayrollEntry entry : entries) {
            entry.setTransferStatus(TransferStatus.PENDING);
            entry.setTransferReference(null);
            entry.setPaystackTransferCode(null);
            payrollEntryRepository.save(entry);
        }
    }

    static String generateReference(PayrollEntry entry) {
        String tenantPrefix = entry.getPayrollRun().getTenant().getId().toString().substring(0, 8);
        return String.format("SAL-%s-%s-%s",
                tenantPrefix,
                entry.getPayrollRun().getPeriod(),
                entry.getEmployee().getEmployeeNumber());
    }
}
