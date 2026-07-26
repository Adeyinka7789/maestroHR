package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.disbursement.provider.CSVDisbursementProvider;
import com.admtechhub.maestrohr.payroll.DisbursementService;
import com.admtechhub.maestrohr.payroll.DisbursementTransactionPhases;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackApiException;
import com.admtechhub.maestrohr.paystack.PaystackClient.PaystackUnknownStateException;
import com.admtechhub.maestrohr.paystack.dto.PaystackRequest;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.platform.JobSweepQueries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration-level tests for {@link DisbursementService}. As of the self-invocation fix,
 * DisbursementService no longer implements the 3 disbursement phases itself — it calls into
 * {@link DisbursementTransactionPhases} (a separate bean, so @Transactional genuinely applies)
 * and makes the Paystack HTTP calls itself, with no open transaction while it does. These tests
 * verify the orchestration sequencing (which phase runs when, in response to which outcome) with
 * both dependencies mocked. Phase-1 validation logic and generateReference moved to
 * {@code DisbursementTransactionPhasesTest} along with their tests. Real-DB proof that Phase 1
 * actually commits independently of what Phase 2 does lives in
 * {@code DisbursementPhaseCommitDurabilityTest}; real-DB proof of the reconciler's per-tenant
 * correctness lives in {@code DisbursementReconciliationCrossTenantIsolationTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisbursementServiceTest {

    @Mock PayrollRunRepository payrollRunRepository;
    @Mock PayrollEntryRepository payrollEntryRepository;
    @Mock PaystackClient paystackClient;
    @Mock CSVDisbursementProvider csvDisbursementProvider;
    @Mock DisbursementTransactionPhases transactionPhases;
    @Mock JobSweepQueries jobSweepQueries;

    DisbursementService disbursementService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT_ID.toString());
        disbursementService = new DisbursementService(
                payrollRunRepository, payrollEntryRepository, paystackClient,
                csvDisbursementProvider, transactionPhases, jobSweepQueries);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private DisbursementTransactionPhases.DisbursementAttempt oneTransferAttempt() {
        PaystackRequest.Transfer transfer = PaystackRequest.Transfer.builder()
                .amount(500_000).recipient("RCP_1").reference("SAL-REF-1").reason("Salary").build();
        return new DisbursementTransactionPhases.DisbursementAttempt("BATCH-abc-123", List.of(transfer));
    }

    // ── disburseSalaries orchestration ───────────────────────────────────────────────────────

    @Test
    void disburseSalaries_success_recordsSuccessOutcomeOnly() {
        var attempt = oneTransferAttempt();
        when(transactionPhases.markRunDisbursing(RUN_ID, TENANT_ID)).thenReturn(attempt);

        PaystackResponse response = PaystackResponse.builder().status(true).build();
        when(paystackClient.initiateBulkTransfer(attempt.transfers())).thenReturn(response);

        disbursementService.disburseSalaries(RUN_ID);

        verify(transactionPhases).recordSuccessOutcome(RUN_ID, TENANT_ID, response);
        verify(transactionPhases, never()).rollbackRunToFailed(any(), any());
        verify(transactionPhases, never()).recordReconciliationResult(any(), any(), any());
    }

    @Test
    void disburseSalaries_phase1Throws_paystackNeverCalledAndNoPhase3() {
        when(transactionPhases.markRunDisbursing(RUN_ID, TENANT_ID))
                .thenThrow(new IllegalStateException("Payroll must be APPROVED or FAILED before disbursement. Current status: DRAFT"));

        assertThrows(IllegalStateException.class, () -> disbursementService.disburseSalaries(RUN_ID));

        verify(paystackClient, never()).initiateBulkTransfer(any());
        verify(transactionPhases, never()).recordSuccessOutcome(any(), any(), any());
        verify(transactionPhases, never()).rollbackRunToFailed(any(), any());
        verify(transactionPhases, never()).recordReconciliationResult(any(), any(), any());
    }

    @Test
    void disburseSalaries_paystackRejectsOutright_rollsBackToFailed() {
        var attempt = oneTransferAttempt();
        when(transactionPhases.markRunDisbursing(RUN_ID, TENANT_ID)).thenReturn(attempt);
        when(paystackClient.initiateBulkTransfer(attempt.transfers()))
                .thenThrow(new PaystackApiException("Insufficient balance"));

        disbursementService.disburseSalaries(RUN_ID);

        verify(transactionPhases).rollbackRunToFailed(RUN_ID, TENANT_ID);
        verify(transactionPhases, never()).recordSuccessOutcome(any(), any(), any());
        verify(transactionPhases, never()).recordReconciliationResult(any(), any(), any());
    }

    @Test
    void disburseSalaries_networkTimeout_attemptsImmediateVerificationThenRecordsReconciliation() {
        var attempt = oneTransferAttempt();
        when(transactionPhases.markRunDisbursing(RUN_ID, TENANT_ID)).thenReturn(attempt);
        when(paystackClient.initiateBulkTransfer(attempt.transfers()))
                .thenThrow(new PaystackUnknownStateException("timed out", new RuntimeException("boom")));

        PaystackResponse.Data data = PaystackResponse.Data.builder().status("success").build();
        PaystackResponse verification = PaystackResponse.builder().status(true).data(data).build();
        when(paystackClient.verifyBulkTransfer(attempt.batchReference())).thenReturn(verification);

        disbursementService.disburseSalaries(RUN_ID);

        verify(paystackClient).verifyBulkTransfer(attempt.batchReference());
        verify(transactionPhases).recordReconciliationResult(RUN_ID, TENANT_ID, "success");
        verify(transactionPhases, never()).recordSuccessOutcome(any(), any(), any());
        verify(transactionPhases, never()).rollbackRunToFailed(any(), any());
    }

    @Test
    void disburseSalaries_networkTimeoutThenVerificationItselfFails_recordsReconciliationWithNullStatus() {
        var attempt = oneTransferAttempt();
        when(transactionPhases.markRunDisbursing(RUN_ID, TENANT_ID)).thenReturn(attempt);
        when(paystackClient.initiateBulkTransfer(attempt.transfers()))
                .thenThrow(new PaystackUnknownStateException("timed out", new RuntimeException("boom")));
        when(paystackClient.verifyBulkTransfer(attempt.batchReference()))
                .thenThrow(new RuntimeException("verification call also failed"));

        disbursementService.disburseSalaries(RUN_ID);

        verify(transactionPhases).recordReconciliationResult(RUN_ID, TENANT_ID, null);
    }

    // ── reconcileUnknownDisbursements tenant-context handling ────────────────────────────────

    @Test
    void reconcileUnknownDisbursements_noBatchReference_rollsBackWithoutCallingPaystack() {
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(jobSweepQueries.findDisbursingUnknownRuns())
                .thenReturn(List.of(new JobSweepQueries.DisbursingUnknownRow(tenantId, runId, null)));

        disbursementService.reconcileUnknownDisbursements();

        verify(transactionPhases).rollbackRunToFailed(runId, tenantId);
        verify(paystackClient, never()).verifyBulkTransfer(anyString());
        assertNull(TenantContext.getCurrentTenant(), "tenant context must be cleared after the sweep");
    }

    @Test
    void reconcileUnknownDisbursements_twoTenants_eachOutcomeMatchesItsOwnRunNotTheOther() {
        UUID tenantA = UUID.randomUUID();
        UUID runA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID runB = UUID.randomUUID();

        when(jobSweepQueries.findDisbursingUnknownRuns()).thenReturn(List.of(
                new JobSweepQueries.DisbursingUnknownRow(tenantA, runA, "BATCH-A"),
                new JobSweepQueries.DisbursingUnknownRow(tenantB, runB, "BATCH-B")));

        // Tenant A's batch verifies as success, tenant B's as failed - proves the per-run
        // outcome tracks its OWN batch reference/tenant rather than bleeding into the other.
        when(paystackClient.verifyBulkTransfer("BATCH-A")).thenReturn(PaystackResponse.builder()
                .status(true).data(PaystackResponse.Data.builder().status("success").build()).build());
        when(paystackClient.verifyBulkTransfer("BATCH-B")).thenReturn(PaystackResponse.builder()
                .status(true).data(PaystackResponse.Data.builder().status("failed").build()).build());
        when(transactionPhases.recordReconciliationResult(runA, tenantA, "success"))
                .thenReturn(DisbursementTransactionPhases.ReconciliationOutcome.CONFIRMED_SUCCESS);
        when(transactionPhases.recordReconciliationResult(runB, tenantB, "failed"))
                .thenReturn(DisbursementTransactionPhases.ReconciliationOutcome.CONFIRMED_FAILED);

        disbursementService.reconcileUnknownDisbursements();

        verify(transactionPhases).recordReconciliationResult(runA, tenantA, "success");
        verify(transactionPhases).recordReconciliationResult(runB, tenantB, "failed");
        verify(transactionPhases, never()).recordReconciliationResult(runA, tenantA, "failed");
        verify(transactionPhases, never()).recordReconciliationResult(runB, tenantB, "success");
        assertNull(TenantContext.getCurrentTenant(), "tenant context must be cleared after the sweep");
    }

    @Test
    void reconcileUnknownDisbursements_oneRunErrors_otherStillProcessedAndContextCleared() {
        UUID tenantA = UUID.randomUUID();
        UUID runA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID runB = UUID.randomUUID();

        when(jobSweepQueries.findDisbursingUnknownRuns()).thenReturn(List.of(
                new JobSweepQueries.DisbursingUnknownRow(tenantA, runA, "BATCH-A"),
                new JobSweepQueries.DisbursingUnknownRow(tenantB, runB, "BATCH-B")));

        when(paystackClient.verifyBulkTransfer("BATCH-A")).thenThrow(new RuntimeException("network blip"));
        when(paystackClient.verifyBulkTransfer("BATCH-B")).thenReturn(PaystackResponse.builder()
                .status(true).data(PaystackResponse.Data.builder().status("failed").build()).build());
        when(transactionPhases.recordReconciliationResult(runB, tenantB, "failed"))
                .thenReturn(DisbursementTransactionPhases.ReconciliationOutcome.CONFIRMED_FAILED);

        disbursementService.reconcileUnknownDisbursements();

        verify(transactionPhases, never()).recordReconciliationResult(eq(runA), any(), any());
        verify(transactionPhases).recordReconciliationResult(runB, tenantB, "failed");
        assertNull(TenantContext.getCurrentTenant(), "tenant context must be cleared even after a mid-loop error");
    }

    // ── retryFailedTransfers orchestration ───────────────────────────────────────────────────

    private DisbursementTransactionPhases.RetryAttempt retryAttempt(List<UUID> entryIds) {
        PaystackRequest.Transfer transfer = PaystackRequest.Transfer.builder()
                .amount(500_000).recipient("RCP_1").reference("SAL-REF-1-R123").reason("Salary retry").build();
        return new DisbursementTransactionPhases.RetryAttempt(List.of(transfer), entryIds);
    }

    @Test
    void retryFailedTransfers_success_marksEntriesDisbursing() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        var attempt = retryAttempt(ids);
        when(transactionPhases.markFailedEntriesRetrying(RUN_ID, TENANT_ID)).thenReturn(attempt);
        when(paystackClient.initiateBulkTransfer(attempt.transfers()))
                .thenReturn(PaystackResponse.builder().status(true).build());

        DisbursementService.RetryResult result = disbursementService.retryFailedTransfers(RUN_ID);

        verify(transactionPhases).finalizeRetry(RUN_ID, TENANT_ID, ids,
                com.admtechhub.maestrohr.payroll.TransferStatus.DISBURSING);
        org.junit.jupiter.api.Assertions.assertEquals(2, result.count());
    }

    @Test
    void retryFailedTransfers_noEligible_skipsPaystack() {
        when(transactionPhases.markFailedEntriesRetrying(RUN_ID, TENANT_ID))
                .thenReturn(new DisbursementTransactionPhases.RetryAttempt(List.of(), List.of()));

        DisbursementService.RetryResult result = disbursementService.retryFailedTransfers(RUN_ID);

        verify(paystackClient, never()).initiateBulkTransfer(any());
        verify(transactionPhases, never()).finalizeRetry(any(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(0, result.count());
    }

    @Test
    void retryFailedTransfers_paystackRejects_marksEntriesFailedAndThrows() {
        List<UUID> ids = List.of(UUID.randomUUID());
        var attempt = retryAttempt(ids);
        when(transactionPhases.markFailedEntriesRetrying(RUN_ID, TENANT_ID)).thenReturn(attempt);
        when(paystackClient.initiateBulkTransfer(attempt.transfers()))
                .thenThrow(new PaystackApiException("Insufficient balance"));

        assertThrows(IllegalStateException.class, () -> disbursementService.retryFailedTransfers(RUN_ID));

        verify(transactionPhases).finalizeRetry(RUN_ID, TENANT_ID, ids,
                com.admtechhub.maestrohr.payroll.TransferStatus.FAILED);
    }

    @Test
    void retryFailedTransfers_unknownState_leavesEntriesDisbursingForWebhook() {
        List<UUID> ids = List.of(UUID.randomUUID());
        var attempt = retryAttempt(ids);
        when(transactionPhases.markFailedEntriesRetrying(RUN_ID, TENANT_ID)).thenReturn(attempt);
        when(paystackClient.initiateBulkTransfer(attempt.transfers()))
                .thenThrow(new PaystackUnknownStateException("timeout", new RuntimeException("boom")));

        DisbursementService.RetryResult result = disbursementService.retryFailedTransfers(RUN_ID);

        verify(transactionPhases).finalizeRetry(RUN_ID, TENANT_ID, ids,
                com.admtechhub.maestrohr.payroll.TransferStatus.DISBURSING);
        org.junit.jupiter.api.Assertions.assertEquals(1, result.count());
    }
}
